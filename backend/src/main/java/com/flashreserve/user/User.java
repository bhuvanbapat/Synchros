package com.flashreserve.user;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fr_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String role;

    @Column(name = "account_state", nullable = false)
    private String accountState;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public UUID getPublicId() { return publicId; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getRole() { return role; }
    public String getAccountState() { return accountState; }
    public Instant getCreatedAt() { return createdAt; }

    void setEmail(String email) { this.email = email; }
    void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    void setRole(String role) { this.role = role; }
    void setAccountState(String accountState) { this.accountState = accountState; }

    @PrePersist
    void onCreate() {
        if (publicId == null) publicId = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
        if (role == null) role = "USER";
        if (accountState == null) accountState = "ACTIVE";
    }
}
