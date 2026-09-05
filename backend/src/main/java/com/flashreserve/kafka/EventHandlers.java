package com.flashreserve.kafka;

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

    public static final String GROUP = "flashreserve";

    private final ProcessedEventRepository processedRepository;
    private final com.flashreserve.notification.NotificationService notificationService;
    private final com.flashreserve.analytics.AnalyticsService analyticsService;

    public EventHandlers(ProcessedEventRepository processedRepository,
                         com.flashreserve.notification.NotificationService notificationService,
                         com.flashreserve.analytics.AnalyticsService analyticsService) {
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

    private void markProcessed(UUID eventId) {
        try {
            processedRepository.saveAndFlush(new ProcessedEvent(eventId, GROUP));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Concurrent duplicate delivery on another thread — already marked.
            log.debug("duplicate event suppressed eventId={}", eventId);
        }
    }
}
