package com.flashreserve.kafka;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.KafkaException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Kafka consumers with end-to-end idempotency and bounded-error handling.
 *
 * Registered only when flashreserve.kafka.enabled=true so integration tests
 * that exercise the pure-Postgres paths don't spin up listener containers.
 *
 * Delivery semantics: at-least-once. Duplicates are made harmless by the
 * processed_event insert (UNIQUE (event_id, consumer_group)) inside the
 * SAME transaction as the business effect:
 *  - handler's transaction begins
 *  - insert the processed marker; duplicate key => already processed => commit nothing
 *  - otherwise process business effect; marker + effect commit atomically
 *
 * NOTE on transactions: listeners dispatch to a SEPARATE bean
 * (EventHandlers) — @Transactional only works through the Spring proxy, so
 * the listener methods must not call the transactional handlers via
 * this.method() (self-invocation bypasses the proxy).
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

    private final DeadLetterService deadLetterService;
    private final EventHandlers handlers;
    private final ObjectMapper objectMapper;

    public EventConsumers(DeadLetterService deadLetterService,
                          EventHandlers handlers,
                          ObjectMapper objectMapper) {
        this.deadLetterService = deadLetterService;
        this.handlers = handlers;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "reservation-events", groupId = EventHandlers.GROUP)
    public void onReservationEvent(String value) {
        handle("reservation-events", value, handlers::processReservation);
    }

    @KafkaListener(topics = "order-events", groupId = EventHandlers.GROUP)
    public void onOrderEvent(String value) {
        handle("order-events", value, handlers::processOrder);
    }

    @KafkaListener(topics = "payment-events", groupId = EventHandlers.GROUP)
    public void onPaymentEvent(String value) {
        handle("payment-events", value, handlers::processPayment);
    }

    @FunctionalInterface
    private interface Handler {
        void apply(JsonNode envelope) throws Exception;
    }

    /**
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
            deadLetterService.quarantine(topic, null, value,
                    "malformed-json: " + e.getMessage(), 0);
            return;
        }

        String eventIdText = envelope.path("eventId").asText(null);
        UUID eventId = parseUuid(eventIdText);
        if (eventId == null) {
            deadLetterService.quarantine(topic, null, value, "missing or invalid eventId", 0);
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
                deadLetterService.quarantine(topic, eventId, value, e.getMessage(), attempts);
                attemptCounts.remove(dedupKey);
                return;
            }
            log.warn("consumer error topic={} eventId={} attempt={}: {}",
                    topic, eventId, attempts, e.getMessage());
            throw new KafkaException("retryable consumer failure", e);
        }
    }

    private static UUID parseUuid(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
