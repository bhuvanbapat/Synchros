package com.Synchros.kafka;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Consumer-side processed-event marker: (event_id, consumer_group) is
 * UNIQUE, so a duplicate delivery is detected by insert-conflict and the
 * handler short-circuits before any business effect.
 */
@Entity
@Table(name = "processed_event")
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "consumer_group", nullable = false)
    private String consumerGroup;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    public ProcessedEvent() {
    }

    public ProcessedEvent(UUID eventId, String consumerGroup) {
        this.eventId = eventId;
        this.consumerGroup = consumerGroup;
    }

    public Long getId() { return id; }
    public UUID getEventId() { return eventId; }
    public String getConsumerGroup() { return consumerGroup; }
    public Instant getProcessedAt() { return processedAt; }

    @PrePersist
    void onCreate() {
        if (processedAt == null) processedAt = Instant.now();
    }
}
