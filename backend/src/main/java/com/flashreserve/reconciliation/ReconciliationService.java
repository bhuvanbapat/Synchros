package com.flashreserve.reconciliation;

import com.flashreserve.inventory.InventoryPool;
import com.flashreserve.inventory.InventoryPoolRepository;
import com.flashreserve.order.Order;
import com.flashreserve.order.OrderRepository;
import com.flashreserve.reservation.Reservation;
import com.flashreserve.reservation.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Consistency auditor. Verifies the core inventory invariant:
 *
 *   For every pool:  total == available + (units currently held) + (units sold)
 *
 * where held = SUM(quantity) of HELD reservations for that section and
 * sold = SUM(quantity) of CONFIRMED reservations. Also detects:
 *   - negative pool counters (impossible by CHECK, but checked anyway)
 *   - reservations in HELD whose TTL lapsed long ago (job stuck)
 *   - orders stuck PENDING_PAYMENT on confirmed/expired reservations
 *
 * Runs on demand via admin API and in integration tests with seeded
 * inconsistencies (which reconciliation must detect).
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final InventoryPoolRepository poolRepository;
    private final ReservationRepository reservationRepository;
    private final OrderRepository orderRepository;

    public ReconciliationService(InventoryPoolRepository poolRepository,
                                 ReservationRepository reservationRepository,
                                 OrderRepository orderRepository) {
        this.poolRepository = poolRepository;
        this.reservationRepository = reservationRepository;
        this.orderRepository = orderRepository;
    }

    public record Finding(String pool, String issue, String detail) {
    }

    public record ReconciliationReport(Instant at, int poolsChecked,
                                       List<Finding> findings, boolean consistent) {
    }

    /**
     * Runs in a REPEATABLE READ transaction so the pool counters and the
     * reservation rows are read from ONE consistent snapshot — a concurrent
     * expiration or confirm commit cannot make the report contradict itself
     * (e.g. old pool counter + new reservation state).
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ReconciliationReport reconcile() {
        List<Finding> findings = new ArrayList<>();
        List<InventoryPool> pools = poolRepository.findAll();

        for (InventoryPool pool : pools) {
            String label = "event=" + pool.getEventId() + " section=" + pool.getSection();

            if (pool.getAvailable() < 0 || pool.getAvailable() > pool.getTotal()) {
                findings.add(new Finding(label, "POOL_COUNTER_INVALID",
                        "available=" + pool.getAvailable() + " total=" + pool.getTotal()));
            }

            int held = 0;
            int sold = 0;
            List<Reservation> forPool = reservationRepository
                    .findByEventId(pool.getEventId()).stream()
                    .filter(r -> r.getSection().equals(pool.getSection()))
                    .toList();
            for (Reservation r : forPool) {
                switch (r.getState()) {
                    case HELD -> held += r.getQuantity();
                    case CONFIRMED -> sold += r.getQuantity();
                    default -> { }
                }
            }

            // HELD reservations whose TTL lapsed > 30s ago indicate a stuck job.
            long stuck = forPool.stream()
                    .filter(r -> r.getState() == Reservation.State.HELD
                            && r.getHoldExpiresAt().isBefore(Instant.now().minusSeconds(30)))
                    .count();
            if (stuck > 0) {
                findings.add(new Finding(label, "STUCK_EXPIRED_HOLDS",
                        stuck + " HELD reservation(s) past TTL"));
            }

            int expectedAvailable = pool.getTotal() - held - sold;
            if (pool.getAvailable() != expectedAvailable) {
                findings.add(new Finding(label, "POOL_MISMATCH",
                        "available=" + pool.getAvailable()
                                + " expected=" + expectedAvailable
                                + " (held=" + held + " sold=" + sold + ")"));
            }
        }

        // Orphaned orders: PENDING_PAYMENT on a non-HELD reservation.
        // Bounded query instead of scanning every order in the system.
        for (Order o : orderRepository.findByState(Order.State.PENDING_PAYMENT)) {
            Reservation r = reservationRepository
                    .findById(o.getReservationId()).orElse(null);
            if (r != null && r.getState() != Reservation.State.HELD) {
                findings.add(new Finding("order=" + o.getPublicId(),
                        "ORPHANED_PENDING_ORDER",
                        "order PENDING_PAYMENT but reservation is " + r.getState()));
            }
        }

        var report = new ReconciliationReport(Instant.now(), pools.size(),
                findings, findings.isEmpty());
        log.info("reconciliation: pools={} findings={} consistent={}",
                report.poolsChecked(), findings.size(), report.consistent());
        return report;
    }
}
