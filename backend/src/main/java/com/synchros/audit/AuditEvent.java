package com.Synchros.audit;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false)
    private String operation;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private String entityId;

    @Column(nullable = false)
    private String result;

    @Column(name = "request_id")
    private String requestId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String details;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public AuditEvent() {
    }

    public AuditEvent(String actor, String operation, String entityType,
                      String entityId, String result, String requestId, String detailsJson) {
        this.actor = actor;
        this.operation = operation;
        this.entityType = entityType;
        this.entityId = entityId;
        this.result = result;
        this.requestId = requestId;
        this.details = detailsJson;
    }

    public Long getId() { return id; }
    public String getActor() { return actor; }
    public String getOperation() { return operation; }
    public String getEntityType() { return entityType; }
    public String getEntityId() { return entityId; }
    public String getResult() { return result; }
    public String getRequestId() { return requestId; }
    public String getDetails() { return details; }
    public Instant getCreatedAt() { return createdAt; }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (result == null) result = "SUCCESS";
    }
}
