package com.flashreserve.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Custom business metrics surfaced at /actuator/prometheus.
 */
@Component
public class FlashMetrics {

    private final Timer reservationTimer;
    private final Counter reservationSuccess;
    private final Counter inventoryUnavailable;
    private final Counter idempotentReplays;
    private final Counter expiredHolds;

    public FlashMetrics(MeterRegistry registry) {
        this.reservationTimer = Timer.builder("flashreserve_reservation_duration")
                .description("Reservation creation latency")
                .register(registry);
        this.reservationSuccess = Counter.builder("flashreserve_reservation_outcome")
                .tag("outcome", "success").register(registry);
        this.inventoryUnavailable = Counter.builder("flashreserve_reservation_outcome")
                .tag("outcome", "inventory_unavailable").register(registry);
        this.idempotentReplays = Counter.builder("flashreserve_idempotent_replays")
                .register(registry);
        this.expiredHolds = Counter.builder("flashreserve_expired_holds")
                .register(registry);
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

    public void idempotentReplay() {
        idempotentReplays.increment();
    }

    public void holdExpired() {
        expiredHolds.increment();
    }
}
