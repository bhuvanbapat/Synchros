package com.flashreserve.analytics;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** Raw event feed for the analytics projection (eventually consistent). */
@Entity
@Table(name = "analytics_event")
public class AnalyticsEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_category", nullable = false)
    private String eventCategory;   // RESERVATION | ORDER | PAYMENT

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public AnalyticsEvent() {
    }

    public AnalyticsEvent(String eventCategory, String eventType, String payload) {
        this.eventCategory = eventCategory;
        this.eventType = eventType;
        this.payload = payload;
    }

    public Long getId() { return id; }
    public String getEventCategory() { return eventCategory; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
