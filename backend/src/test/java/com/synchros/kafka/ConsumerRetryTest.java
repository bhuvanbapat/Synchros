package com.synchros.kafka;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Durable retry-counter regression (V6): the attempt count must live in
 * Postgres, not process memory — "bounded retry" bounded globally, not
 * bounded per uptime.
 */
class ConsumerRetryTest {

    @Test
    void counterStartsAtOneAndIncrements() {
        ConsumerRetry row = new ConsumerRetry("reservation-events:x", 1, "boom");
        assertEquals(1, row.getAttempts());
        assertEquals("boom", row.getLastError());
        assertNotNull(row.getUpdatedAt());

        row.recordAttempt("boom2");
        assertEquals(2, row.getAttempts());
        assertEquals("boom2", row.getLastError());
    }

    @Test
    void updatedAtRefreshesOnEachAttempt() throws InterruptedException {
        ConsumerRetry row = new ConsumerRetry("k", 1, "e");
        Instant before = row.getUpdatedAt();
        Thread.sleep(5);
        row.recordAttempt("e");
        assertTrue(row.getUpdatedAt().isAfter(before));
    }
}
