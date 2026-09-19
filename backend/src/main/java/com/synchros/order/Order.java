package com.synchros.order;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Order state machine:
 *
 *   PENDING_PAYMENT ──pay ok──▶ CONFIRMED (terminal)
 *          │
 *          ├──pay fail──▶ FAILED (terminal)
 *          ├──timeout───▶ EXPIRED  (hold released by expiration job)
 *          └──cancel────▶ CANCELLED (terminal)
 */
@Entity
@Table(name = "fr_order")
public class Order {

    public enum State { PENDING_PAYMENT, CONFIRMED, FAILED, CANCELLED, EXPIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "reservation_id", nullable = false, unique = true)
    private Long reservationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private State state;

    @Column(name = "amount_cents", nullable = false)
    private int amountCents;

    @Column(nullable = false)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "failed_at")
    private Instant failedAt;

    @Version
    private long version;

    public Order() {
    }

    public Order(Long userId, Long reservationId, int amountCents, String currency) {
        this.userId = userId;
        this.reservationId = reservationId;
        this.amountCents = amountCents;
        this.currency = currency;
        this.state = State.PENDING_PAYMENT;
    }

    public void transitionTo(State target, Instant now) {
        State current = this.state;
        boolean legal = switch (current) {
            case PENDING_PAYMENT -> target == State.CONFIRMED || target == State.FAILED
                    || target == State.CANCELLED || target == State.EXPIRED;
            case CONFIRMED, FAILED, CANCELLED, EXPIRED -> false;
        };
        if (!legal) {
            throw new IllegalStateException(
                    "Illegal order transition " + current + " -> " + target);
        }
        this.state = target;
        if (target == State.CONFIRMED) this.confirmedAt = now;
        if (target == State.FAILED) this.failedAt = now;
    }

    public Long getId() { return id; }
    public UUID getPublicId() { return publicId; }
    public Long getUserId() { return userId; }
    public Long getReservationId() { return reservationId; }
    public State getState() { return state; }
    public int getAmountCents() { return amountCents; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getFailedAt() { return failedAt; }

    @PrePersist
    void onCreate() {
        if (publicId == null) publicId = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }
}
