package com.flashreserve.outbox;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Appends events to the transactional outbox. MUST be called within the
 * caller's transaction — the outbox row commits atomically with the
 * business change, guaranteeing at-least-once publication later.
 */
@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void append(String eventType, String aggregateType, String aggregateId,
                       Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            outboxRepository.save(new OutboxEvent(eventType, aggregateType, aggregateId, json));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize outbox payload", e);
        }
    }

    public record OutboxDto(UUID eventId, String eventType, String aggregateType,
                            String aggregateId, String payload, String state,
                            int retryCount) {
        public static OutboxDto from(OutboxEvent e) {
            return new OutboxDto(e.getEventId(), e.getEventType(), e.getAggregateType(),
                    e.getAggregateId(), e.getPayload(), e.getState().name(), e.getRetryCount());
        }
    }
}
