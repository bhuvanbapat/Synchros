package com.Synchros.kafka;

import com.Synchros.analytics.AnalyticsService;
import com.Synchros.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Consumer logic was previously exercised only via a live broker in compose;
 * these unit tests pin the contract the brokers rely on: dedup by
 * (eventId, group), atomic marker+effect, poison quarantine after bounded
 * retries, malformed payload isolation.
 */
class EventHandlersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProcessedEventRepository processedRepository;
    private NotificationService notificationService;
    private AnalyticsService analyticsService;
    private EventHandlers handlers;

    @BeforeEach
    void setUp() {
        processedRepository = mock(ProcessedEventRepository.class);
        notificationService = mock(NotificationService.class);
        analyticsService = mock(AnalyticsService.class);
        handlers = new EventHandlers(processedRepository, notificationService,
                analyticsService);
    }

    private static JsonNode envelope(String eventType, UUID eventId) {
        return MAPPER.readTree("{\"eventId\":\"" + eventId + "\"," +
                "\"eventType\":\"" + eventType + "\"," +
                "\"aggregateId\":\"agg-1\"," +
                "\"payload\":{\"userId\":42,\"quantity\":1}}");
    }

    @Test
    void reservationConfirmedProducesNotificationAndAnalytics() {
        UUID eventId = UUID.randomUUID();
        when(processedRepository.findByEventIdAndConsumerGroup(eventId,
                EventHandlers.GROUP)).thenReturn(Optional.empty());

        handlers.processReservation(envelope("ReservationConfirmed", eventId));

        verify(notificationService).notify(eq(42L), eq("ReservationConfirmed"), anyString());
        verify(analyticsService).recordReservationEvent(eq("ReservationConfirmed"), anyString());
        verify(processedRepository).saveAndFlush(any(ProcessedEvent.class));
    }

    @Test
    void duplicateDeliveryIsSuppressedBeforeAnyBusinessEffect() {
        UUID eventId = UUID.randomUUID();
        when(processedRepository.findByEventIdAndConsumerGroup(eventId,
                EventHandlers.GROUP)).thenReturn(Optional.of(new ProcessedEvent()));

        handlers.processReservation(envelope("ReservationConfirmed", eventId));

        verifyNoInteractions(notificationService);
        verifyNoInteractions(analyticsService);
        verify(processedRepository, never()).saveAndFlush(any());
    }

    @Test
    void concurrentDuplicateMarkerInsertIsAbsorbedNotFatal() {
        UUID eventId = UUID.randomUUID();
        when(processedRepository.findByEventIdAndConsumerGroup(eventId,
                EventHandlers.GROUP)).thenReturn(Optional.empty());
        when(processedRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("dup"));

        assertDoesNotThrow(() ->
                handlers.processReservation(envelope("ReservationConfirmed", eventId)));
        verify(analyticsService).recordReservationEvent(anyString(), anyString());
    }

    @Test
    void orderAndPaymentEventsProjectToAnalyticsOnly() {
        UUID orderEvent = UUID.randomUUID();
        UUID paymentEvent = UUID.randomUUID();
        when(processedRepository.findByEventIdAndConsumerGroup(any(), eq(EventHandlers.GROUP)))
                .thenReturn(Optional.empty());

        handlers.processOrder(envelope("OrderCreated", orderEvent));
        handlers.processPayment(envelope("PaymentSucceeded", paymentEvent));

        verify(analyticsService).recordOrderEvent(eq("OrderCreated"), anyString());
        verify(analyticsService).recordPaymentEvent(eq("PaymentSucceeded"), anyString());
        verifyNoInteractions(notificationService);
    }

    // ---- strict payload contract (regression: userId=0 silent rows) ----

    @Test
    void missingUserIdFailsTheHandlerInsteadOfProjectingZero() {
        UUID eventId = UUID.randomUUID();
        when(processedRepository.findByEventIdAndConsumerGroup(eventId,
                EventHandlers.GROUP)).thenReturn(Optional.empty());
        JsonNode badPayload = MAPPER.readTree("{\"eventId\":\"" + eventId + "\"," +
                "\"eventType\":\"ReservationConfirmed\"," +
                "\"payload\":{\"reservationId\":\"x\"}}"); // no userId

        assertThrows(IllegalArgumentException.class,
                () -> handlers.processReservation(badPayload));
        verifyNoInteractions(notificationService);
        verifyNoInteractions(analyticsService);
        verify(processedRepository, never()).saveAndFlush(any());
    }

    @Test
    void nonNumericUserIdFailsTheHandler() {
        UUID eventId = UUID.randomUUID();
        when(processedRepository.findByEventIdAndConsumerGroup(eventId,
                EventHandlers.GROUP)).thenReturn(Optional.empty());
        JsonNode badPayload = MAPPER.readTree("{\"eventId\":\"" + eventId + "\"," +
                "\"eventType\":\"ReservationCancelled\"," +
                "\"payload\":{\"userId\":\"abc\"}}");

        assertThrows(IllegalArgumentException.class,
                () -> handlers.processReservation(badPayload));
        verifyNoInteractions(notificationService);
    }

    @Test
    void zeroOrNegativeUserIdFailsTheHandler() {
        UUID eventId = UUID.randomUUID();
        when(processedRepository.findByEventIdAndConsumerGroup(eventId,
                EventHandlers.GROUP)).thenReturn(Optional.empty());
        JsonNode badPayload = MAPPER.readTree("{\"eventId\":\"" + eventId + "\"," +
                "\"eventType\":\"ReservationExpired\"," +
                "\"payload\":{\"userId\":0}}");

        assertThrows(IllegalArgumentException.class,
                () -> handlers.processReservation(badPayload));
        verifyNoInteractions(notificationService);
    }

    @Test
    void analyticsOnlyEventsTolerateMinimalPayloads() {
        // ReservationCreated carries no notification projection; a thin
        // payload is legal there and must pass.
        UUID eventId = UUID.randomUUID();
        when(processedRepository.findByEventIdAndConsumerGroup(eventId,
                EventHandlers.GROUP)).thenReturn(Optional.empty());
        JsonNode thin = MAPPER.readTree("{\"eventId\":\"" + eventId + "\"," +
                "\"eventType\":\"ReservationCreated\"," +
                "\"payload\":{\"reservationId\":\"x\"}}");

        assertDoesNotThrow(() -> handlers.processReservation(thin));
        verify(analyticsService).recordReservationEvent(eq("ReservationCreated"), anyString());
    }
}
