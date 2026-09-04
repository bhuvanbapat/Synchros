package com.flashreserve.it;

import com.flashreserve.reconciliation.ReconciliationService;
import com.flashreserve.reservation.Reservation;
import com.flashreserve.reservation.ReservationService;
import com.flashreserve.inventory.InventoryPool;
import com.flashreserve.inventory.InventoryPoolRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reconciliation must detect intentionally corrupted data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReconciliationIT extends PostgresIntegrationBase {

    @Autowired ReconciliationService reconciliation;
    @Autowired InventoryPoolRepository poolRepo;
    @Autowired ReservationService reservationService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void healthyDataIsConsistent() {
        var report = reconciliation.reconcile();
        assertTrue(report.consistent(), "seeded demo data must reconcile cleanly: "
                + report.findings());
    }

    @Test
    void detectsManuallyCorruptedCounter() {
        // Create a valid hold, then corrupt the pool counter behind the back
        // of the application (simulating an operational accident). Corrupt
        // WITHIN CHECK bounds (available <= total) so only the logical
        // invariant breaks — proving reconciliation catches what the DB
        // CHECK constraint cannot.
        var setup = hold();
        jdbc.update("UPDATE inventory_pool SET available = ? WHERE id = ?",
                setup.units(), setup.poolId());

        var report = reconciliation.reconcile();
        assertFalse(report.consistent(), "corruption must be detected");
        assertTrue(report.findings().stream().anyMatch(f ->
                        f.issue().equals("POOL_MISMATCH")),
                "counter corruption flagged: " + report.findings());

        // restore
        jdbc.update("UPDATE inventory_pool SET available = ? WHERE id = ?",
                setup.units() - 1, setup.poolId());
    }

    @Test
    void detectsStuckExpiredHold() {
        var setup = hold();
        jdbc.update("UPDATE reservation SET hold_expires_at = now() - interval '1 hour' " +
                "WHERE public_id = ?", setup.reservationPublicId());

        var report = reconciliation.reconcile();
        assertTrue(report.findings().stream().anyMatch(f ->
                        f.issue().equals("STUCK_EXPIRED_HOLDS")),
                "stuck hold flagged: " + report.findings());

        // clean up via the real expiration path
        var r = reservationService.getByPublicIdForUserForSystem(setup.reservationPublicId());
        reservationService.expireOne(r);
        var after = reconciliation.reconcile();
        assertTrue(after.findings().stream().noneMatch(f ->
                        f.issue().equals("STUCK_EXPIRED_HOLDS") &&
                        f.pool().contains(setup.poolSection())),
                "after expiration the stuck hold must clear: " + after.findings());
    }

    record Setup(Long poolId, java.util.UUID reservationPublicId, int units, String poolSection) {
    }

    private Setup hold() {
        InventoryPool pool = seedPool(3);
        Reservation r = reservationService.createReservationInternal(
                1L, pool.getEventId(), pool.getId(), pool.getSection(), 1);
        return new Setup(pool.getId(), r.getPublicId(), 3, pool.getSection());
    }

    private InventoryPool seedPool(int units) {
        try {
            var ctor = InventoryPool.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            InventoryPool p = ctor.newInstance();
            var f1 = InventoryPool.class.getDeclaredField("eventId");
            f1.setAccessible(true); f1.set(p, 1L);
            var f2 = InventoryPool.class.getDeclaredField("section");
            f2.setAccessible(true); f2.set(p, "REC-" + java.util.UUID.randomUUID());
            var f3 = InventoryPool.class.getDeclaredField("total");
            f3.setAccessible(true); f3.set(p, units);
            var f4 = InventoryPool.class.getDeclaredField("available");
            f4.setAccessible(true); f4.set(p, units);
            return poolRepo.save(p);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
