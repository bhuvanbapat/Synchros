package com.synchros.outbox;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Appends events to the transactional outbox. MUST be called within the
 * caller's transaction — MANDATORY propagation makes this contract
 * explicit: called without an active transaction it fails fast instead of
 * silently opening its own (which would break the atomicity guarantee).
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

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String eventType, String aggregateType, String aggregateId,
                       Map<String, Object> payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            // Payload must be serializable BEFORE we touch the outbox row;
            // fail the business transaction rather than committing without
            // its event.
            throw new IllegalStateException("Failed to serialize outbox payload", e);
        }
        outboxRepository.save(new OutboxEvent(eventType, aggregateType, aggregateId, json));
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
