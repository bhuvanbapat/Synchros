package com.flashreserve.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Drains the outbox to Kafka with bounded retries.
 *
 * Reliability model:
 *  - The business transaction + outbox row commit atomically.
 *  - This publisher polls and publishes PENDING/FAILED rows.
 *  - If Kafka is down, rows stay PENDING/FAILED — nothing is lost.
 *  - After maxRetries failures a row is marked DEAD (dead-letter state),
 *    visible via the admin API.
 *  - Kafka producer idempotence is enabled, so broker-side duplicates from
 *    producer retries are deduplicated; consumers are additionally
 *    idempotent (processed_event table).
 */
@Component
@Conditional(com.flashreserve.kafka.ConditionalOnKafkaEnabled.class)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final FlashReserveTopics topics;
    private final int maxRetries;

    public OutboxPublisher(OutboxRepository outboxRepository,
                           KafkaTemplate<String, String> kafkaTemplate,
                           FlashReserveTopics topics,
                           com.flashreserve.config.FlashReserveProperties props) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.topics = topics;
        this.maxRetries = props.getOutbox().getMaxRetries();
    }

    @Scheduled(fixedDelayString = "${flashreserve.outbox.poll-interval-ms:500}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outboxRepository.findPublishable(
                org.springframework.data.domain.Limit.of(50));
        for (OutboxEvent event : batch) {
            publishOne(event);
        }
    }

    void publishOne(OutboxEvent event) {
        String topic = topics.topicFor(event.getEventType());
        String key = event.getAggregateId();
        try {
            kafkaTemplate.send(topic, key, wrap(event)).get();
            event.markPublished();
            log.debug("outbox published eventId={} topic={}", event.getEventId(), topic);
        } catch (Exception e) {
            event.markFailed();
            if (event.getRetryCount() >= maxRetries) {
                event.markDead();
                log.error("outbox event moved to DEAD eventId={} type={} after {} retries",
                        event.getEventId(), event.getEventType(), event.getRetryCount());
            } else {
                log.warn("outbox publish failed eventId={} attempt={} cause={}",
                        event.getEventId(), event.getRetryCount(), e.getMessage());
            }
        }
    }

    private String wrap(OutboxEvent event) {
        return "{\"eventId\":\"" + event.getEventId() + "\"," +
               "\"eventType\":\"" + event.getEventType() + "\"," +
               "\"aggregateType\":\"" + event.getAggregateType() + "\"," +
               "\"aggregateId\":\"" + event.getAggregateId() + "\"," +
               "\"occurredAt\":\"" + Instant.now() + "\"," +
               "\"payload\":" + event.getPayload() + "}";
    }
}
