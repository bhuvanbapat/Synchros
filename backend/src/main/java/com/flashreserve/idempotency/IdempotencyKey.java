package com.flashreserve.idempotency;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "idempotency_key")
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idem_key", nullable = false)
    private String idemKey;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String operation;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(name = "response_status")
    private Integer responseStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", columnDefinition = "jsonb")
    private String responseBody;

    @Column(nullable = false)
    private String state;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public IdempotencyKey() {
    }

    public IdempotencyKey(String idemKey, Long userId, String operation,
                          String requestHash, Instant expiresAt) {
        this.idemKey = idemKey;
        this.userId = userId;
        this.operation = operation;
        this.requestHash = requestHash;
        this.state = "IN_FLIGHT";
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public String getIdemKey() { return idemKey; }
    public Long getUserId() { return userId; }
    public String getOperation() { return operation; }
    public String getRequestHash() { return requestHash; }
    public Integer getResponseStatus() { return responseStatus; }
    public String getResponseBody() { return responseBody; }
    public String getState() { return state; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }

    public void complete(int status, String bodyJson) {
        this.responseStatus = status;
        this.responseBody = bodyJson;
        this.state = "COMPLETED";
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
