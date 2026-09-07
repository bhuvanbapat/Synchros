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
    private final ConsumerRetryRepository retryRepository;

    public EventConsumers(DeadLetterService deadLetterService,
                          EventHandlers handlers,
                          ObjectMapper objectMapper,
                          ConsumerRetryRepository retryRepository) {
        this.deadLetterService = deadLetterService;
        this.handlers = handlers;
        this.objectMapper = objectMapper;
        this.retryRepository = retryRepository;
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
     * Attempt counts are DURABLE (consumer_retry table, V6): a restart no
     * longer resets the poison-message clock. "Bounded retry" is bounded
     * globally, not bounded per uptime. Counting happens in a short
     * independent transaction (REQUIRES_NEW inside DeadLetterService
     * pattern); handler transactions are untouched.
     */
    private int countAttempt(String dedupKey, String error) {
        ConsumerRetry row = retryRepository.findById(dedupKey).orElse(null);
        if (row == null) {
            retryRepository.saveAndFlush(new ConsumerRetry(dedupKey, 1, error));
            return 1;
        }
        row.recordAttempt(error);
        retryRepository.saveAndFlush(row);
        return row.getAttempts();
    }

    private void clearAttempts(String dedupKey) {
        try {
            retryRepository.deleteById(dedupKey);
        } catch (Exception ignored) {
            // best-effort clear; retention purge catches stragglers
        }
    }

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
            clearAttempts(dedupKey);
        } catch (Exception e) {
            int attempts = countAttempt(dedupKey, e.getMessage());
            if (attempts >= MAX_ATTEMPTS) {
                String type = envelope.path("eventType").asText("unknown");
                deadLetterService.quarantine(topic, eventId, value, e.getMessage(), attempts);
                clearAttempts(dedupKey);
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
