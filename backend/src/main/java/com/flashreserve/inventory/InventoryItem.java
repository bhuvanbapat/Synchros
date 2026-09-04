package com.flashreserve.inventory;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Unit-level inventory item (a concrete seat). In V1, holds are tracked in
 * the pooled InventoryPool for the flash-sale GA model; this table exists for
 * seat-accurate domains and demonstrates per-unit state + optimistic version.
 */
@Entity
@Table(name = "inventory_item")
public class InventoryItem {

    public enum State { AVAILABLE, HELD, SOLD, RELEASED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(nullable = false)
    private String section;

    @Column(nullable = false)
    private String identifier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private State state;

    @Version
    private long version;

    @Column(name = "held_until")
    private Instant heldUntil;

    @Column(name = "held_by_reservation")
    private Long heldByReservation;

    public Long getId() { return id; }
    public UUID getPublicId() { return publicId; }
    public Long getEventId() { return eventId; }
    public String getSection() { return section; }
    public String getIdentifier() { return identifier; }
    public State getState() { return state; }
    public long getVersion() { return version; }
    public Instant getHeldUntil() { return heldUntil; }
    public Long getHeldByReservation() { return heldByReservation; }

    @PrePersist
    void onCreate() {
        if (publicId == null) publicId = UUID.randomUUID();
        if (state == null) state = State.AVAILABLE;
    }
}
