package com.Synchros.kafka;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** Poison-message quarantine written after retries are exhausted. */
@Entity
@Table(name = "dead_letter")
public class DeadLetter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "event_type")
    private String eventType;

    @Column(name = "topic")
    private String topic;

    @Column(nullable = false)
    private String error;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String payload;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public DeadLetter() {
    }

    public DeadLetter(UUID eventId, String eventType, String topic, String error,
                      String payload, int attemptCount) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.topic = topic;
        this.error = error;
        this.payload = payload;
        this.attemptCount = attemptCount;
    }

    public Long getId() { return id; }
    public UUID getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getTopic() { return topic; }
    public String getError() { return error; }
    public String getPayload() { return payload; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getCreatedAt() { return createdAt; }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
