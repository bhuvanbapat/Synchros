package com.Synchros.reservation;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Reservation state machine:
 *
 *   HELD ──confirm──▶ CONFIRMED
 *     │                    (terminal)
 *     ├──expire──▶ EXPIRED (terminal)
 *     └──cancel──▶ CANCELLED (terminal)
 *
 * FAILED is reserved for reservation attempts that could not secure a hold.
 * Transitions are guarded in {@link Reservation#transitionTo} — illegal
 * transitions throw, so races (e.g. confirm vs. expire) collapse to exactly
 * one winner because the guard re-reads state under the same row lock.
 */
@Entity
@Table(name = "reservation")
public class Reservation {

    public enum State { HELD, CONFIRMED, EXPIRED, CANCELLED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private State state;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private String section;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "hold_expires_at", nullable = false)
    private Instant holdExpiresAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "expired_at")
    private Instant expiredAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Version
    private long version;

    public Reservation() {
    }

    public Reservation(Long userId, Long eventId, String section, int quantity,
                       Instant now, Instant holdExpiresAt) {
        this.userId = userId;
        this.eventId = eventId;
        this.section = section;
        this.quantity = quantity;
        this.state = State.HELD;
        this.createdAt = now;
        this.holdExpiresAt = holdExpiresAt;
    }

    /**
     * Explicit transition guard. Throws on illegal transitions; callers run
     * inside a transaction so a throw aborts any paired inventory mutation.
     */
    public void transitionTo(State target, Instant now) {
        State current = this.state;
        boolean legal = switch (current) {
            case HELD -> target == State.CONFIRMED || target == State.EXPIRED
                    || target == State.CANCELLED;
            case CONFIRMED, EXPIRED, CANCELLED, FAILED -> false;
        };
        if (!legal) {
            throw new IllegalStateException(
                    "Illegal reservation transition " + current + " -> " + target);
        }
        this.state = target;
        switch (target) {
            case CONFIRMED -> this.confirmedAt = now;
            case EXPIRED -> this.expiredAt = now;
            case CANCELLED -> this.cancelledAt = now;
            default -> { /* HELD/FAILED have no timestamp */ }
        }
    }

    public boolean isTerminal() {
        return state == State.CONFIRMED || state == State.EXPIRED
                || state == State.CANCELLED || state == State.FAILED;
    }

    public Long getId() { return id; }
    public UUID getPublicId() { return publicId; }
    public Long getUserId() { return userId; }
    public Long getEventId() { return eventId; }
    public State getState() { return state; }
    public int getQuantity() { return quantity; }
    public String getSection() { return section; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getHoldExpiresAt() { return holdExpiresAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getExpiredAt() { return expiredAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public long getVersion() { return version; }

    @PrePersist
    void onCreate() {
        if (publicId == null) publicId = UUID.randomUUID();
    }
}
