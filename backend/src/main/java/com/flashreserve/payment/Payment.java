package com.flashreserve.payment;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment")
public class Payment {

    public enum State { INITIATED, SUCCEEDED, FAILED, TIMED_OUT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private State state;

    @Column(name = "amount_cents", nullable = false)
    private int amountCents;

    @Column(name = "provider_ref")
    private String providerRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public Payment() {
    }

    public Payment(Long orderId, int amountCents) {
        this.orderId = orderId;
        this.amountCents = amountCents;
        this.state = State.INITIATED;
    }

    public Long getId() { return id; }
    public UUID getPublicId() { return publicId; }
    public Long getOrderId() { return orderId; }
    public State getState() { return state; }
    public int getAmountCents() { return amountCents; }
    public String getProviderRef() { return providerRef; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }

    public void complete(State finalState, String providerRef, Instant now) {
        this.state = finalState;
        this.providerRef = providerRef;
        this.completedAt = now;
    }

    @PrePersist
    void onCreate() {
        if (publicId == null) publicId = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }
}
