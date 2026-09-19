package com.Synchros.outbox;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Transactional outbox row. Written in the SAME transaction as the business
 * mutation, so an event for a committed change can never be lost — it is
 * published asynchronously by OutboxPublisher with bounded retries and a
 * dead-letter terminal state.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    public enum State { PENDING, PUBLISHED, FAILED, DEAD }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, updatable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private State state;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    /** Publisher claim lease (V7): null = free to claim. */
    @Column(name = "leased_until")
    private Instant leasedUntil;

    public OutboxEvent() {
    }

    public OutboxEvent(String eventType, String aggregateType, String aggregateId,
                       String payloadJson) {
        this.eventType = eventType;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.payload = payloadJson;
        this.state = State.PENDING;
    }

    public Long getId() { return id; }
    public UUID getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getPayload() { return payload; }
    public State getState() { return state; }
    public int getRetryCount() { return retryCount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }

    public void markPublished() {
        this.state = State.PUBLISHED;
        this.publishedAt = Instant.now();
        this.leasedUntil = null; // release any claim
    }

    public void markFailed() {
        this.retryCount++;
        this.state = State.FAILED;
    }

    public void markDead() {
        this.state = State.DEAD;
        this.leasedUntil = null;
    }

    public Instant getLeasedUntil() { return leasedUntil; }

    public void leaseUntil(Instant until) {
        this.leasedUntil = until;
    }

    @PrePersist
    void onCreate() {
        if (eventId == null) eventId = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }
}
