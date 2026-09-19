package com.Synchros.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

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
 *
 * Transaction boundaries: NO Kafka I/O ever happens inside a DB transaction.
 * The batch is fetched in one short read-only transaction; publication is
 * plain I/O; each row's state change is a separate short transaction by id
 * (programmatic TransactionTemplate — annotation self-invocation from the
 * scheduler method would silently skip the proxy). The previous single
 * @Transactional around the whole drain held a pooled connection open
 * across up to 50 blocking Kafka sends: under broker outage it exhausted
 * the pool and turned "Kafka down" into "site down".
 */
@Component
@Conditional(com.Synchros.kafka.ConditionalOnKafkaEnabled.class)
@ConditionalOnProperty(name = "Synchros.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final SynchrosTopics topics;
    private final TransactionTemplate tx;
    private final int maxRetries;

    private static final int BATCH_SIZE = 50;
    /** Claim lifetime: a crashed publisher's rows return after this. */
    private static final java.time.Duration LEASE = java.time.Duration.ofSeconds(60);

    public OutboxPublisher(OutboxRepository outboxRepository,
                           KafkaTemplate<String, String> kafkaTemplate,
                           SynchrosTopics topics,
                           TransactionTemplate tx,
                           com.Synchros.config.SynchrosProperties props) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.topics = topics;
        this.tx = tx;
        this.maxRetries = props.getOutbox().getMaxRetries();
    }

    @Scheduled(fixedDelayString = "${Synchros.outbox.poll-interval-ms:500}")
    public void publishPending() {
        // 1) Claim a disjoint batch (short tx): SKIP LOCKED + an explicit
        //    lease so a second instance cannot re-send rows this instance
        //    is actively publishing. The lease predicate runs inside the
        //    claim SQL; a crashed instance's lease simply expires and the
        //    row returns to the pool. Dirty-checking persists the lease.
        List<OutboxEvent> batch = tx.execute(status ->
                outboxRepository.findPublishable(BATCH_SIZE).stream()
                        .peek(e -> e.leaseUntil(Instant.now().plus(LEASE)))
                        .toList());
        if (batch == null || batch.isEmpty()) return;

        // 2) Publish — NO transaction open, all broker I/O outside tx.
        for (OutboxEvent event : batch) {
            publishOne(event);
        }
    }

    void publishOne(OutboxEvent event) {
        String topic = topics.topicFor(event.getEventType());
        String key = event.getAggregateId();
        try {
            // Blocking send with NO transaction open. Producer idempotence
            // (acks=all + enable.idempotence) makes this effectively
            // at-most-once to the broker despite producer retries.
            kafkaTemplate.send(topic, key, wrap(event)).get();

            tx.executeWithoutResult(status ->
                    outboxRepository.findById(event.getId())
                            .ifPresent(OutboxEvent::markPublished));
            log.debug("outbox published eventId={} topic={}", event.getEventId(), topic);
        } catch (Exception e) {
            final int attemptsSoFar = event.getRetryCount();
            final boolean dead = attemptsSoFar + 1 >= maxRetries;
            // One short transaction: state flip + backoff lease together,
            // so no window exists where a FAILED row sits unleased.
            tx.executeWithoutResult(status ->
                    outboxRepository.findById(event.getId()).ifPresent(row -> {
                        if (dead) {
                            row.markDead();
                        } else {
                            row.markFailed();   // increments retry_count
                            // Exponential backoff lease: 2^retry seconds,
                            // capped at 60 — a dying broker gets polled
                            // less, not hammered at a fixed 500ms.
                            row.leaseUntil(Instant.now().plusSeconds(
                                    Math.min(60, 1L << Math.min(6, row.getRetryCount()))));
                        }
                    }));
            if (dead) {
                log.error("outbox event moved to DEAD eventId={} type={} after {} retries",
                        event.getEventId(), event.getEventType(), attemptsSoFar + 1);
            } else {
                log.warn("outbox publish failed eventId={} attempt={} cause={}",
                        event.getEventId(), attemptsSoFar + 1, e.getMessage());
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
