package com.synchros.inventory;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Pooled inventory counter for a section. This is the authoritative
 * consistency boundary: the conditional UPDATE
 *   SET available = available - :qty WHERE id = :id AND available >= :qty
 * is a single-statement atomic compare-and-set guarded by the row lock
 * PostgreSQL takes when executing it, plus the CHECK constraint
 * (available >= 0 AND available <= total) as a final backstop.
 */
@Entity
@Table(name = "inventory_pool")
public class InventoryPool {

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
    private int total;

    @Column(nullable = false)
    private int available;

    @Version
    private long version;

    public InventoryPool() {
    }

    public Long getId() { return id; }
    public UUID getPublicId() { return publicId; }
    public Long getEventId() { return eventId; }
    public String getSection() { return section; }
    public int getTotal() { return total; }
    public int getAvailable() { return available; }
    public long getVersion() { return version; }

    @PrePersist
    void onCreate() {
        if (publicId == null) publicId = UUID.randomUUID();
    }
}
