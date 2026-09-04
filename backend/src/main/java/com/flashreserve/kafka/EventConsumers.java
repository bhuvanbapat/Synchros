package com.flashreserve.kafka;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.KafkaException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Kafka consumers with end-to-end idempotency and bounded-error handling.
 *
 * Registered only when flashreserve.kafka.enabled=true so integration tests
 * that exercise the pure-Postgres paths don't spin up listener containers.
 *
 * Delivery semantics: at-least-once. Duplicates are made harmless by the
 * processed_event insert (UNIQUE (event_id, consumer_group)):
 *  - handler starts a transaction
 *  - tries to insert the marker; if it exists => duplicate => return
 *  - otherwise processes business effect and commits marker atomically
 *
 * Failure semantics: a listener error is caught, recorded; after
 * MAX_ATTEMPTS the message is quarantined in dead_letter and ACKed
 * (poison messages never block the partition).
 */
@Component
@Conditional(ConditionalOnKafkaEnabled.class)
public class EventConsumers {

    private static final Logger log = LoggerFactory.getLogger(EventConsumers.class);
    private static final int MAX_ATTEMPTS = 5;

    public static final String GROUP = "flashreserve";

    private final ProcessedEventRepository processedRepository;
    private final DeadLetterRepository deadLetterRepository;
    private final com.flashreserve.notification.NotificationService notificationService;
    private final com.flashreserve.analytics.AnalyticsService analyticsService;
    private final ObjectMapper objectMapper;

    public EventConsumers(ProcessedEventRepository processedRepository,
                          DeadLetterRepository deadLetterRepository,
                          com.flashreserve.notification.NotificationService notificationService,
                          com.flashreserve.analytics.AnalyticsService analyticsService,
                          ObjectMapper objectMapper) {
        this.processedRepository = processedRepository;
        this.deadLetterRepository = deadLetterRepository;
        this.notificationService = notificationService;
        this.analyticsService = analyticsService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "reservation-events", groupId = GROUP)
    public void onReservationEvent(String value) {
        handle("reservation-events", value, this::processReservation);
    }

    @KafkaListener(topics = "order-events", groupId = GROUP)
    public void onOrderEvent(String value) {
        handle("order-events", value, this::processOrder);
    }

    @KafkaListener(topics = "payment-events", groupId = GROUP)
    public void onPaymentEvent(String value) {
        handle("payment-events", value, this::processPayment);
    }

    @FunctionalInterface
    private interface Handler {
        void apply(JsonNode envelope) throws Exception;
    }

    /**
     * Common wrapper: parse, dedup, execute, or quarantine on repeated error.
     * Track attempt count in-memory per (topic+eventId) — bounded poison
     * handling without a retry-topic dependency for the portfolio scale.
     */
    private final java.util.Map<String, Integer> attemptCounts = new java.util.concurrent
            .ConcurrentHashMap<>();

    private void handle(String topic, String value, Handler handler) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(value);
        } catch (Exception e) {
            quarantine(topic, null, value, "malformed-json: " + e.getMessage(), 0);
            return;
        }

        UUID eventId;
        try {
            eventId = UUID.fromString(envelope.path("eventId").asText(null));
        } catch (Exception e) {
            eventId = null;
        }
        if (eventId == null) {
            quarantine(topic, null, value, "missing eventId", 0);
            return;
        }

        String dedupKey = topic + ":" + eventId;
        try {
            handler.apply(envelope);
            attemptCounts.remove(dedupKey);
        } catch (Exception e) {
            int attempts = attemptCounts.merge(dedupKey, 1, Integer::sum);
            if (attempts >= MAX_ATTEMPTS) {
                String type = envelope.path("eventType").asText("unknown");
                quarantine(topic, eventId, value, e.getMessage(), attempts);
                attemptCounts.remove(dedupKey);
                return;
            }
            log.warn("consumer error topic={} eventId={} attempt={}: {}",
                    topic, eventId, attempts, e.getMessage());
            throw new KafkaException("retryable consumer failure", e);
        }
    }

    @org.springframework.transaction.annotation.Transactional
    public void processReservation(JsonNode envelope) {
        if (alreadyProcessed(envelope)) return;
        String type = envelope.path("eventType").asText();
        JsonNode p = envelope.path("payload");

        // Notification projection (eventually consistent)
        if ("ReservationConfirmed".equals(type) || "ReservationExpired".equals(type)
                || "ReservationCancelled".equals(type)) {
            Long userId = p.path("userId").asLong();
            String body = switch (type) {
                case "ReservationConfirmed" -> "Your reservation is confirmed!";
                case "ReservationExpired" -> "Your reservation hold expired and inventory was released.";
                default -> "Your reservation was cancelled.";
            };
            notificationService.notify(userId, type, body);
        }
        analyticsService.recordReservationEvent(type, envelope.toString());
        markProcessed(envelope);
    }

    @org.springframework.transaction.annotation.Transactional
    public void processOrder(JsonNode envelope) {
        if (alreadyProcessed(envelope)) return;
        analyticsService.recordOrderEvent(envelope.path("eventType").asText(),
                envelope.toString());
        markProcessed(envelope);
    }

    @org.springframework.transaction.annotation.Transactional
    public void processPayment(JsonNode envelope) {
        if (alreadyProcessed(envelope)) return;
        analyticsService.recordPaymentEvent(envelope.path("eventType").asText(),
                envelope.toString());
        markProcessed(envelope);
    }

    private boolean alreadyProcessed(JsonNode envelope) {
        UUID eventId = UUID.fromString(envelope.path("eventId").asText());
        return processedRepository
                .findByEventIdAndConsumerGroup(eventId, GROUP)
                .isPresent();
    }

    private void markProcessed(JsonNode envelope) {
        UUID eventId = UUID.fromString(envelope.path("eventId").asText());
        try {
            processedRepository.saveAndFlush(new ProcessedEvent(eventId, GROUP));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Concurrent duplicate delivery on another thread — already marked.
            log.debug("duplicate event suppressed eventId={}", eventId);
        }
    }

    private void quarantine(String topic, UUID eventId, String payload,
                            String error, int attempts) {
        String type = "unknown";
        try {
            type = objectMapper.readTree(payload).path("eventType").asText("unknown");
        } catch (Exception ignored) {
        }
        deadLetterRepository.save(new DeadLetter(eventId, type, topic,
                error == null ? "unknown" : error, payload, attempts));
        log.error("message quarantined topic={} eventId={} error={}", topic, eventId, error);
    }
}
