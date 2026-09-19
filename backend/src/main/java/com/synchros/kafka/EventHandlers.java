package com.synchros.kafka;

import tools.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Transactional Kafka event handlers. Lives in its own bean so the
 * @KafkaListener in EventConsumers invokes it through the Spring proxy —
 * @Transactional on a method called from the same class (self-invocation)
 * is silently ignored, which would break the marker+effect atomicity.
 */
@Component
@Conditional(ConditionalOnKafkaEnabled.class)
public class EventHandlers {

    private static final Logger log = LoggerFactory.getLogger(EventHandlers.class);

    public static final String GROUP = "Synchros";

    private final ProcessedEventRepository processedRepository;
    private final com.synchros.notification.NotificationService notificationService;
    private final com.synchros.analytics.AnalyticsService analyticsService;

    public EventHandlers(ProcessedEventRepository processedRepository,
                         com.synchros.notification.NotificationService notificationService,
                         com.synchros.analytics.AnalyticsService analyticsService) {
        this.processedRepository = processedRepository;
        this.notificationService = notificationService;
        this.analyticsService = analyticsService;
    }

    @Transactional
    public void processReservation(JsonNode envelope) {
        UUID eventId = UUID.fromString(envelope.path("eventId").asText());
        if (alreadyProcessed(eventId)) return;
        String type = envelope.path("eventType").asText();
        JsonNode p = envelope.path("payload");

        // Notification projection (eventually consistent). Payload contract:
        // userId must be a present positive number — a malformed payload
        // must NOT silently project a userId=0 notification row (the old
        // asLong() default); it fails the handler, retries, and dead-letters.
        if ("ReservationConfirmed".equals(type) || "ReservationExpired".equals(type)
                || "ReservationCancelled".equals(type)) {
            Long userId = requiredPositiveLong(p, "userId", eventId);
            String body = switch (type) {
                case "ReservationConfirmed" -> "Your reservation is confirmed!";
                case "ReservationExpired" -> "Your reservation hold expired and inventory was released.";
                default -> "Your reservation was cancelled.";
            };
            notificationService.notify(userId, type, body);
        }
        analyticsService.recordReservationEvent(type, envelope.toString());
        markProcessed(eventId);
    }

    @Transactional
    public void processOrder(JsonNode envelope) {
        UUID eventId = UUID.fromString(envelope.path("eventId").asText());
        if (alreadyProcessed(eventId)) return;
        analyticsService.recordOrderEvent(envelope.path("eventType").asText(),
                envelope.toString());
        markProcessed(eventId);
    }

    @Transactional
    public void processPayment(JsonNode envelope) {
        UUID eventId = UUID.fromString(envelope.path("eventId").asText());
        if (alreadyProcessed(eventId)) return;
        analyticsService.recordPaymentEvent(envelope.path("eventType").asText(),
                envelope.toString());
        markProcessed(eventId);
    }

    private boolean alreadyProcessed(UUID eventId) {
        return processedRepository
                .findByEventIdAndConsumerGroup(eventId, GROUP)
                .isPresent();
    }

    /**
     * Strict payload contract check: the field must exist, be numeric,
     * and be > 0. Returns the value; throws otherwise so the consumer
     * retry/dead-letter machinery handles bad payloads like any other
     * poison message instead of silently projecting defaults.
     */
    private static Long requiredPositiveLong(JsonNode payload, String field, UUID eventId) {
        JsonNode node = payload.path(field);
        if (!node.isNumber() || node.asLong() <= 0) {
            throw new IllegalArgumentException("payload field '" + field
                    + "' missing or invalid for event " + eventId);
        }
        return node.asLong();
    }

    private void markProcessed(UUID eventId) {
        try {
            processedRepository.saveAndFlush(new ProcessedEvent(eventId, GROUP));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Concurrent duplicate delivery on another thread — already marked.
            log.debug("duplicate event suppressed eventId={}", eventId);
        }
    }
}
