package com.Synchros.kafka;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One durable retry counter per failing (topic, eventId). */
@Entity
@Table(name = "consumer_retry")
public class ConsumerRetry {

    @Id
    private String dedupKey;

    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public ConsumerRetry() {
    }

    public ConsumerRetry(String dedupKey, int attempts, String lastError) {
        this.dedupKey = dedupKey;
        this.attempts = attempts;
        this.lastError = lastError;
        this.updatedAt = Instant.now();
    }

    public String getDedupKey() { return dedupKey; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public Instant getUpdatedAt() { return updatedAt; }

    void recordAttempt(String error) {
        this.attempts++;
        this.lastError = error;
        this.updatedAt = Instant.now();
    }
}
