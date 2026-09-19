package com.synchros.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Type-safe application properties (synchros.* in application.yml). */
@ConfigurationProperties(prefix = "synchros")
public class SynchrosProperties {

    private int holdDurationSeconds = 120;
    private final Expiration expiration = new Expiration();
    private final RateLimit rateLimit = new RateLimit();
    private final Outbox outbox = new Outbox();
    private final Payment payment = new Payment();

    public static class Expiration {
        private long scanIntervalMs = 1000;
        private int batchSize = 100;

        public long getScanIntervalMs() { return scanIntervalMs; }
        public void setScanIntervalMs(long v) { this.scanIntervalMs = v; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int v) { this.batchSize = v; }
    }

    public static class RateLimit {
        private int reservationsPerMinute = 30;
        private int burst = 10;

        public int getReservationsPerMinute() { return reservationsPerMinute; }
        public void setReservationsPerMinute(int v) { this.reservationsPerMinute = v; }
        public int getBurst() { return burst; }
        public void setBurst(int v) { this.burst = v; }
    }

    public static class Outbox {
        private long pollIntervalMs = 500;
        private int maxRetries = 10;

        public long getPollIntervalMs() { return pollIntervalMs; }
        public void setPollIntervalMs(long v) { this.pollIntervalMs = v; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int v) { this.maxRetries = v; }
    }

    /** Mock payment gateway outcome weights (should sum to ~1.0). */
    public static class Payment {
        private double successWeight = 0.85;
        private double failureWeight = 0.10;
        private double timeoutWeight = 0.05;

        public double getSuccessWeight() { return successWeight; }
        public void setSuccessWeight(double v) { this.successWeight = v; }
        public double getFailureWeight() { return failureWeight; }
        public void setFailureWeight(double v) { this.failureWeight = v; }
        public double getTimeoutWeight() { return timeoutWeight; }
        public void setTimeoutWeight(double v) { this.timeoutWeight = v; }
    }

    public int getHoldDurationSeconds() { return holdDurationSeconds; }
    public void setHoldDurationSeconds(int v) { this.holdDurationSeconds = v; }
    public Expiration getExpiration() { return expiration; }
    public RateLimit getRateLimit() { return rateLimit; }
    public Outbox getOutbox() { return outbox; }
    public Payment getPayment() { return payment; }
}
