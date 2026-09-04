package com.flashreserve.notification;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Notification() {
    }

    public Notification(Long userId, String kind, String body) {
        this.userId = userId;
        this.kind = kind;
        this.body = body;
    }

    public Long getId() { return id; }
    public UUID getPublicId() { return publicId; }
    public Long getUserId() { return userId; }
    public String getKind() { return kind; }
    public String getBody() { return body; }
    public Instant getCreatedAt() { return createdAt; }

    @PrePersist
    void onCreate() {
        if (publicId == null) publicId = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }
}
