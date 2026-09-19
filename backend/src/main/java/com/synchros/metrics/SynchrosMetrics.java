package com.synchros.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Custom business metrics surfaced at /actuator/prometheus.
 */
@Component
public class SynchrosMetrics {

    private final Timer reservationTimer;
    private final Counter reservationSuccess;
    private final Counter inventoryUnavailable;
    private final Counter idempotentReplays;
    private final Counter expiredHolds;
    private final Counter reservationFailures;
    private final Counter reconFindings;
    private final io.micrometer.core.instrument.Gauge reconConsistent;
    private final java.util.concurrent.atomic.AtomicLong lastConsistent;

    public SynchrosMetrics(MeterRegistry registry) {
        this.reservationTimer = Timer.builder("Synchros_reservation_duration")
                .description("Reservation creation latency")
                .register(registry);
        this.reservationSuccess = Counter.builder("Synchros_reservation_outcome")
                .tag("outcome", "success").register(registry);
        this.inventoryUnavailable = Counter.builder("Synchros_reservation_outcome")
                .tag("outcome", "inventory_unavailable").register(registry);
        this.reservationFailures = Counter.builder("Synchros_reservation_outcome")
                .tag("outcome", "error").register(registry);
        this.idempotentReplays = Counter.builder("Synchros_idempotent_replays")
                .register(registry);
        this.expiredHolds = Counter.builder("Synchros_expired_holds")
                .register(registry);
        this.reconFindings = Counter.builder("Synchros_reconciliation_findings")
                .description("Consistency findings raised by reconciliation runs")
                .register(registry);
        this.lastConsistent = new java.util.concurrent.atomic.AtomicLong(0);
        this.reconConsistent = io.micrometer.core.instrument.Gauge
                .builder("Synchros_reconciliation_consistent",
                        this.lastConsistent::get)
                .description("1 = last scheduled reconciliation found 0 findings")
                .register(registry);
    }

    public void reconciliationOutcome(int findings) {
        lastConsistent.set(findings == 0 ? 1 : 0);
        for (int i = 0; i < findings; i++) {
            reconFindings.increment();
        }
    }

    public <T> T timeReservation(java.util.function.Supplier<T> supplier) {
        return reservationTimer.record(supplier);
    }

    public void reservationSuccess() {
        reservationSuccess.increment();
    }

    public void inventoryUnavailable() {
        inventoryUnavailable.increment();
    }

    public void reservationError() {
        reservationFailures.increment();
    }

    public void idempotentReplay() {
        idempotentReplays.increment();
    }

    public void holdExpired() {
        expiredHolds.increment();
    }
}
