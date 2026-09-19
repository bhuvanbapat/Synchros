package com.Synchros.it;

import com.Synchros.common.DomainException;
import com.Synchros.inventory.InventoryPool;
import com.Synchros.inventory.InventoryPoolRepository;
import com.Synchros.order.Order;
import com.Synchros.order.OrderService;
import com.Synchros.reservation.Reservation;
import com.Synchros.reservation.ReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for the order-create/expiration race (fixed with a
 * pessimistic reservation lock in createOrderForReservation):
 *
 *   order creation (lock + HELD check + order INSERT)
 *   races
 *   expiration job (conditional EXPIRED flip + inventory release)
 *
 * Legal outcomes only:
 *   - create wins: order exists AND reservation stays HELD
 *   - expire wins: create sees EXPIRED and 409s; no orphan order
 * The illegal outcome — an order on an EXPIRED reservation — must never
 * appear (reconciliation's ORPHANED_PENDING_ORDER would flag it).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OrderCreateExpirationRaceIT extends PostgresIntegrationBase {

    @Autowired ReservationService reservationService;
    @Autowired InventoryPoolRepository poolRepo;
    @Autowired OrderService orderService;
    @Autowired JdbcTemplate jdbc;

    private record Setup(InventoryPool pool, Reservation reservation) {
    }

    private Setup hold() throws Exception {
        var ctor = InventoryPool.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        InventoryPool p = ctor.newInstance();
        var f1 = InventoryPool.class.getDeclaredField("eventId");
        f1.setAccessible(true); f1.set(p, 1L);
        var f2 = InventoryPool.class.getDeclaredField("section");
        f2.setAccessible(true); f2.set(p, "RACE-" + java.util.UUID.randomUUID());
        var f3 = InventoryPool.class.getDeclaredField("total");
        f3.setAccessible(true); f3.set(p, 2);
        var f4 = InventoryPool.class.getDeclaredField("available");
        f4.setAccessible(true); f4.set(p, 2);
        InventoryPool pool = poolRepo.save(p);

        Reservation r = reservationService.createReservationInternal(
                1L, pool.getEventId(), pool.getId(), pool.getSection(), 1);
        // Backdate so the expiration job path is eligible immediately.
        jdbc.update("UPDATE reservation SET hold_expires_at = now() - interval '5 minutes' " +
                "WHERE id = ?", r.getId());
        return new Setup(pool, r);
    }

    @Test
    void createAndExpireRaceNeverYieldsOrphanOrder() throws Exception {
        int rounds = 20;
        AtomicInteger orphanOrders = new AtomicInteger();
        AtomicInteger legalConflicts = new AtomicInteger();
        AtomicInteger createWins = new AtomicInteger();
        // Reservations where create won the race remain HELD past their
        // (backdated) TTL: expire them after the assertions so the shared
        // test container is left clean for other IT classes
        // (ReconciliationIT asserts a clean world).
        var createdHolds = new java.util.ArrayList<Reservation>();

        for (int round = 0; round < rounds; round++) {
            Setup setup = hold();
            var barrier = new CyclicBarrier(2);

            Future<Order> createFuture = Executors.newSingleThreadExecutor().submit(() -> {
                barrier.await();
                return orderService.createOrderForReservation(
                        setup.reservation().getPublicId(), 1L);
            });
            Future<Boolean> expireFuture = Executors.newSingleThreadExecutor().submit(() -> {
                barrier.await();
                return reservationService.expireOne(
                        reservationService.getByPublicIdForUserForSystem(
                                setup.reservation().getPublicId()));
            });
            try {
                Order order = createFuture.get(20, TimeUnit.SECONDS);
                createWins.incrementAndGet();
                createdHolds.add(setup.reservation());
                // create won => expire must have lost AND reservation must
                // still be HELD (the lock ordered them).
                Boolean expired = expireFuture.get(20, TimeUnit.SECONDS);
                if (Boolean.TRUE.equals(expired)) {
                    // Both claimed success — for create+expire this is ILLEGAL.
                    orphanOrders.incrementAndGet();
                }
                var state = reservationService
                        .getByPublicIdForUserForSystem(setup.reservation().getPublicId())
                        .getState();
                if (state != Reservation.State.HELD) {
                    orphanOrders.incrementAndGet();
                }
            } catch (ExecutionException e) {
                if (e.getCause() instanceof DomainException de
                        && de.getCode() == DomainException.ErrorCode.RESERVATION_EXPIRED) {
                    legalConflicts.incrementAndGet();
                    Boolean expired = expireFuture.get(20, TimeUnit.SECONDS);
                    assertTrue(Boolean.TRUE.equals(expired),
                            "if create lost, expire must have won");
                } else {
                    throw e;
                }
            }
        }

        assertEquals(0, orphanOrders.get(), "illegal outcomes observed");
        assertTrue(createWins.get() + legalConflicts.get() == rounds);
        System.out.printf("RACE (create-vs-expire): %d rounds, create won %d, expire won %d%n",
                rounds, createWins.get(), legalConflicts.get());

        // Cleanup: expire the holds this test legitimately left behind —
        // pairing reservation expiry with pending-order closure exactly like
        // ReservationExpirationJob does, so the shared container is left
        // consistent (no stuck holds, no orphaned pending orders).
        for (Reservation r : createdHolds) {
            try {
                if (reservationService.expireOne(
                        reservationService.getByPublicIdForUserForSystem(r.getPublicId()))) {
                    orderService.expireIfPending(r.getId());
                }
            } catch (Exception ignored) {
                // already terminal — fine
            }
        }
    }

    @Test
    void concurrentOrderCreatesForSameReservationYieldOneOrder() throws Exception {
        Setup setup = hold();
        int callers = 8;
        ExecutorService ex = Executors.newFixedThreadPool(callers);
        var barrier = new CyclicBarrier(callers);
        var orders = new java.util.concurrent.ConcurrentHashMap<
                java.util.UUID, AtomicBoolean>();
        var futures = new java.util.ArrayList<Future<?>>();
        for (int i = 0; i < callers; i++) {
            futures.add(ex.submit(() -> {
                barrier.await();
                try {
                    Order o = orderService.createOrderForReservation(
                            setup.reservation().getPublicId(), 1L);
                    orders.putIfAbsent(o.getPublicId(), new AtomicBoolean());
                    return o;
                } catch (Exception e) {
                    return e;
                }
            }));
        }
        ex.shutdown();
        assertTrue(ex.awaitTermination(60, TimeUnit.SECONDS));

        // All successful callers must have received the SAME order row
        // (idempotent create via reservation UNIQUE constraint + lock).
        long distinct = futures.stream().map(f -> {
            try { return f.get(); } catch (Exception e) { return e; }
        }).filter(r -> r instanceof Order).map(r -> ((Order) r).getPublicId())
                .distinct().count();
        long orderRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fr_order WHERE reservation_id = ?",
                Long.class, setup.reservation().getId());
        assertTrue(orderRows <= 1, "duplicate orders for one reservation: " + orderRows);
        assertTrue(distinct <= 1, "callers saw different orders: " + distinct);

        // Cleanup: the winning create left a HELD hold past its backdated
        // TTL — close it out exactly like ReservationExpirationJob does
        // (reservation expiry + pending-order closure) so the shared
        // container stays consistent.
        try {
            if (reservationService.expireOne(
                    reservationService.getByPublicIdForUserForSystem(
                            setup.reservation().getPublicId()))) {
                orderService.expireIfPending(setup.reservation().getId());
            }
        } catch (Exception ignored) {
            // already terminal — fine
        }
    }
}
