package com.Synchros.catalog;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fr_event")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    @Column(name = "venue_id", nullable = false)
    private Long venueId;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "on_sale_at", nullable = false)
    private Instant onSaleAt;

    @Column(nullable = false)
    private String state;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public UUID getPublicId() { return publicId; }
    public Long getVenueId() { return venueId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getOnSaleAt() { return onSaleAt; }
    public String getState() { return state; }

    void setVenueId(Long venueId) { this.venueId = venueId; }
    void setName(String name) { this.name = name; }
    void setDescription(String description) { this.description = description; }
    void setStartsAt(Instant startsAt) { this.startsAt = startsAt; }
    void setState(String state) { this.state = state; }

    @PrePersist
    void onCreate() {
        if (publicId == null) publicId = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
        if (state == null) state = "SCHEDULED";
        if (onSaleAt == null) onSaleAt = Instant.now();
    }
}
