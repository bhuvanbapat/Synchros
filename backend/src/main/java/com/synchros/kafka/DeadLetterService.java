package com.Synchros.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Persists poison messages to dead_letter. REQUIRES_NEW so the quarantine
 * row commits even if the caller is inside a transaction that rolls back
 * (and so it works when called with no transaction at all).
 */
@Component
@Conditional(ConditionalOnKafkaEnabled.class)
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    private final DeadLetterRepository deadLetterRepository;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    public DeadLetterService(DeadLetterRepository deadLetterRepository,
                             tools.jackson.databind.ObjectMapper objectMapper) {
        this.deadLetterRepository = deadLetterRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void quarantine(String topic, UUID eventId, String payload,
                           String error, int attempts) {
        String type = "unknown";
        try {
            type = objectMapper.readTree(payload).path("eventType").asText("unknown");
        } catch (Exception ignored) {
            // payload is not even parseable — that IS the poison condition
        }
        deadLetterRepository.save(new DeadLetter(eventId, type, topic,
                error == null ? "unknown" : error, payload, attempts));
        log.error("message quarantined topic={} eventId={} error={}", topic, eventId, error);
    }
}
