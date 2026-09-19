package com.Synchros.kafka;

import com.Synchros.analytics.AnalyticsService;
import com.Synchros.notification.NotificationService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * DeadLetterService contract: poison payloads (even unparseable ones) are
 * persisted with their topic/error/attempts; a payload without a valid
 * eventId is quarantined immediately with zero attempts.
 */
class DeadLetterServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void quarantinePersistsPoisonMessage() {
        DeadLetterRepository repo = mock(DeadLetterRepository.class);
        DeadLetterService service = new DeadLetterService(repo, MAPPER);

        UUID eventId = UUID.randomUUID();
        String payload = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"PaymentSucceeded\"}";
        service.quarantine("payment-events", eventId, payload, "boom", 5);

        var captor = org.mockito.ArgumentCaptor.forClass(DeadLetter.class);
        verify(repo).save(captor.capture());
        DeadLetter saved = captor.getValue();
        assertEquals(eventId, saved.getEventId());
        assertEquals("PaymentSucceeded", saved.getEventType());
        assertEquals("payment-events", saved.getTopic());
        assertEquals("boom", saved.getError());
        assertEquals(5, saved.getAttemptCount());
        assertEquals(payload, saved.getPayload());
    }

    @Test
    void quarantineHandlesUnparseablePayload() {
        DeadLetterRepository repo = mock(DeadLetterRepository.class);
        DeadLetterService service = new DeadLetterService(repo, MAPPER);

        assertDoesNotThrow(() ->
                service.quarantine("reservation-events", null, "not-json{{{", "malformed", 1));

        var captor = org.mockito.ArgumentCaptor.forClass(DeadLetter.class);
        verify(repo).save(captor.capture());
        assertEquals("unknown", captor.getValue().getEventType());
        assertNotNull(captor.getValue().getError());
    }
}
