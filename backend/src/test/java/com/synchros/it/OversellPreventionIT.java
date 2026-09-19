package com.synchros.it;

import com.synchros.config.SynchrosProperties;
import com.synchros.inventory.InventoryPool;
import com.synchros.inventory.InventoryPoolRepository;
import com.synchros.outbox.OutboxService;
import com.synchros.reservation.Reservation;
import com.synchros.reservation.ReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE OVERSELL PREVENTION TEST.
 *
 * Scenario: pool of 10 units; 100 concurrent reservation attempts.
 * Invariant that must hold after the dust settles:
 *   - successful reservations <= 10
 *   - pool.available >= 0
 *   - SUM(held+confirmed quantities) == 10 - available
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OversellPreventionIT extends PostgresIntegrationBase {

    @Autowired InventoryPoolRepository poolRepo;
    @Autowired ReservationService reservationService;
    @Autowired SynchrosProperties props;

    record Result(boolean ok, String reservationId, String error) {
    }

    @Test
    void tenUnitsOneHundredConcurrentRequestsNeverOversell() throws Exception {
        // seed a dedicated pool
        InventoryPool pool = new InventoryPool();
        var seedRow = poolRepo.save(newPool(10));
        int qty = 1;

        int clients = 100;
        ExecutorService pool2 = Executors.newFixedThreadPool(clients);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();

        List<Future<Result>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < clients; i++) {
            futures.add(pool2.submit(() -> {
                start.await();
                try {
                    Reservation r = reservationService.createReservationInternal(
                            1L, seedRow.getEventId(), seedRow.getId(),
                            "HOT-SECTION", qty);
                    successes.incrementAndGet();
                    return new Result(true, r.getPublicId().toString(), null);
                } catch (Exception e) {
                    return new Result(false, null, e.getMessage());
                }
            }));
        }
        start.countDown();
        pool2.shutdown();
        assertTrue(pool2.awaitTermination(120, TimeUnit.SECONDS), "test timed out");

        int ok = (int) futures.stream().filter(f -> {
            try {
                return f.get().ok();
            } catch (Exception e) {
                return false;
            }
        }).count();

        InventoryPool after = poolRepo.findById(seedRow.getId()).orElseThrow();

        // --- THE INVARIANTS ---
        assertEquals(successes.get(), ok, "success counter must match");
        assertTrue(ok <= 10, "OVERSOLD! successes=" + ok + " > 10");
        assertTrue(after.getAvailable() >= 0, "negative inventory: " + after.getAvailable());
        assertEquals(10 - ok, after.getAvailable(),
                "available must equal total - successfulHolds");
        assertEquals(10, after.getTotal());

        System.out.printf("OVERSELL TEST: %d clients, %d succeeded, %d rejected, available=%d%n",
                clients, ok, clients - ok, after.getAvailable());
    }

    @Test
    void repeatedRunsRemainCorrect() throws Exception {
        for (int run = 1; run <= 3; run++) {
            InventoryPool pool = poolRepo.save(newPool(20));
            final int runNum = run;
            int clients = 60;
            ExecutorService pool2 = Executors.newFixedThreadPool(clients);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < clients; i++) {
                futures.add(pool2.submit(() -> {
                    start.await();
                    try {
                        reservationService.createReservationInternal(
                                1L, pool.getEventId(), pool.getId(), "RERUN-" + runNum, 1);
                        return true;
                    } catch (Exception e) {
                        return false;
                    }
                }));
            }
            start.countDown();
            pool2.shutdown();
            assertTrue(pool2.awaitTermination(120, TimeUnit.SECONDS));

            int ok = (int) futures.stream().filter(f -> {
                try { return f.get(); } catch (Exception e) { return false; }
            }).count();
            InventoryPool after = poolRepo.findById(pool.getId()).orElseThrow();
            assertTrue(ok <= 20, "run " + run + ": oversold successes=" + ok);
            assertEquals(20 - ok, after.getAvailable(), "run " + run + ": available mismatch");
            System.out.printf("RERUN %d: %d succeeded, available=%d%n", run, ok, after.getAvailable());
        }
    }

    private InventoryPool newPool(int units) {
        try {
            var ctor = InventoryPool.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            InventoryPool p = ctor.newInstance();
            var f1 = InventoryPool.class.getDeclaredField("eventId");
            f1.setAccessible(true);
            f1.set(p, 1L);
            var f2 = InventoryPool.class.getDeclaredField("section");
            f2.setAccessible(true);
            f2.set(p, "HOT-" + UUID.randomUUID());
            var f3 = InventoryPool.class.getDeclaredField("total");
            f3.setAccessible(true);
            f3.set(p, units);
            var f4 = InventoryPool.class.getDeclaredField("available");
            f4.setAccessible(true);
            f4.set(p, units);
            return p;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
