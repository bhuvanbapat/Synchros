package com.Synchros.it;

import com.Synchros.config.SynchrosProperties;
import com.Synchros.inventory.InventoryPool;
import com.Synchros.inventory.InventoryPoolRepository;
import com.Synchros.outbox.OutboxRepository;
import com.Synchros.reservation.Reservation;
import com.Synchros.reservation.ReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Expiration tests: TTL release, and the confirm-vs-expire race.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ExpirationIT extends PostgresIntegrationBase {

    @Autowired ReservationService reservationService;
    @Autowired InventoryPoolRepository poolRepo;
    @Autowired SynchrosProperties props;
    @Autowired OutboxRepository outboxRepo;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void expiredHoldReleasesInventory() {
        InventoryPool pool = seedPool(5, "EXP-A");
        Reservation r = reservationService.createReservationInternal(
                1L, pool.getEventId(), pool.getId(), pool.getSection(), 2);

        // Simulate TTL lapse: backdate the hold.
        backdateHold(r.getId());

        boolean expired = reservationService.expireOne(
                reservationService.getByPublicIdForUserForSystem(r.getPublicId()));

        assertTrue(expired);
        InventoryPool after = poolRepo.findById(pool.getId()).orElseThrow();
        assertEquals(5, after.getAvailable(), "released units must return to pool");
    }

    @Test
    void confirmWinsRaceAgainstExpiry() throws Exception {
        InventoryPool pool = seedPool(5, "RACE-A");
        Reservation r = reservationService.createReservationInternal(
                1L, pool.getEventId(), pool.getId(), pool.getSection(), 1);
        backdateHold(r.getId());

        // Confirm and expire concurrently, many times over the same base state.
        ExecutorService ex = Executors.newFixedThreadPool(2);
        var confirmFuture = ex.submit(() ->
                reservationService.confirm(r.getPublicId(), 1L));
        var expireFuture = ex.submit(() ->
                reservationService.expireOne(
                        reservationService.getByPublicIdForUserForSystem(r.getPublicId())));

        Reservation confirmed = confirmFuture.get(20, TimeUnit.SECONDS);
        boolean expired = expireFuture.get(20, TimeUnit.SECONDS);
        ex.shutdown();

        // Exactly one winner in {confirm, expire}:
        assertTrue(confirmed.getState() == Reservation.State.CONFIRMED
                        || confirmed.getState() == Reservation.State.EXPIRED,
                "final state must be legal, got " + confirmed.getState());
        assertNotEquals(Boolean.TRUE, expired,
                "if confirm won, expire must report false");

        if (confirmed.getState() == Reservation.State.CONFIRMED) {
            InventoryPool after = poolRepo.findById(pool.getId()).orElseThrow();
            assertEquals(4, after.getAvailable(),
                    "confirmed hold must NOT release inventory");
        } else {
            InventoryPool after = poolRepo.findById(pool.getId()).orElseThrow();
            assertEquals(5, after.getAvailable(),
                    "expired hold must release inventory");
        }
    }

    @Test
    void confirmAfterExpiryThrows() {
        InventoryPool pool = seedPool(5, "EXP-B");
        Reservation r = reservationService.createReservationInternal(
                1L, pool.getEventId(), pool.getId(), pool.getSection(), 1);
        backdateHold(r.getId());
        assertTrue(reservationService.expireOne(
                reservationService.getByPublicIdForUserForSystem(r.getPublicId())));

        assertThrows(Exception.class, () ->
                reservationService.confirm(r.getPublicId(), 1L),
                "confirming an expired hold must fail");
        assertEquals(5, poolRepo.findById(pool.getId()).orElseThrow().getAvailable());
    }

    // ---- helpers ----

    private InventoryPool seedPool(int units, String section) {
        try {
            var ctor = InventoryPool.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            InventoryPool p = ctor.newInstance();
            var f1 = InventoryPool.class.getDeclaredField("eventId");
            f1.setAccessible(true); f1.set(p, 1L);
            var f2 = InventoryPool.class.getDeclaredField("section");
            f2.setAccessible(true); f2.set(p, section + "-" + UUID.randomUUID());
            var f3 = InventoryPool.class.getDeclaredField("total");
            f3.setAccessible(true); f3.set(p, units);
            var f4 = InventoryPool.class.getDeclaredField("available");
            f4.setAccessible(true); f4.set(p, units);
            return poolRepo.save(p);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void backdateHold(Long id) {
        jdbc.update("UPDATE reservation SET hold_expires_at = now() - interval '10 minutes' " +
                "WHERE id = ?", id);
    }
}
