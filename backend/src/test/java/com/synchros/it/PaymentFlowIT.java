package com.synchros.it;

import com.synchros.inventory.InventoryPool;
import com.synchros.inventory.InventoryPoolRepository;
import com.synchros.order.Order;
import com.synchros.order.OrderService;
import com.synchros.payment.MockPaymentGateway;
import com.synchros.reservation.Reservation;
import com.synchros.reservation.ReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PaymentFlowIT extends PostgresIntegrationBase {

    @Autowired ReservationService reservationService;
    @Autowired InventoryPoolRepository poolRepo;
    @Autowired OrderService orderService;

    @Test
    void successfulPaymentConfirmsOrderAndReservation() {
        var setup = holdAndOrder();
        Order order = setup.order();

        Order result = orderService.applyPaymentResult(
                order.getPublicId(), setup.userId(), "ref-ok-1",
                MockPaymentGateway.Outcome.SUCCESS);

        assertEquals(Order.State.CONFIRMED, result.getState());
        assertEquals(Reservation.State.CONFIRMED,
                reservationService.getByPublicIdForUserForSystem(
                        setup.reservation().getPublicId()).getState());
        // inventory NOT released on success
        assertEquals(setup.units() - setup.qty(),
                poolRepo.findById(setup.pool().getId()).orElseThrow().getAvailable());
    }

    @Test
    void failedPaymentCancelsReservationAndReleasesInventory() {
        var setup = holdAndOrder();
        Order order = setup.order();

        Order result = orderService.applyPaymentResult(
                order.getPublicId(), setup.userId(), "ref-fail-1",
                MockPaymentGateway.Outcome.FAILURE);

        assertEquals(Order.State.FAILED, result.getState());
        assertEquals(Reservation.State.CANCELLED,
                reservationService.getByPublicIdForUserForSystem(
                        setup.reservation().getPublicId()).getState());
        assertEquals(setup.units(),
                poolRepo.findById(setup.pool().getId()).orElseThrow().getAvailable(),
                "failed payment must release held units");
    }

    @Test
    void duplicateSuccessCallbacksAreIdempotent() {
        var setup = holdAndOrder();
        Order order = setup.order();

        Order first = orderService.applyPaymentResult(
                order.getPublicId(), setup.userId(), "ref-dup-1",
                MockPaymentGateway.Outcome.SUCCESS);
        assertEquals(Order.State.CONFIRMED, first.getState());

        // Second callback with the SAME ref: absorbed, no state change, no crash.
        Order second = orderService.applyPaymentResult(
                order.getPublicId(), setup.userId(), "ref-dup-1",
                MockPaymentGateway.Outcome.SUCCESS);
        assertEquals(Order.State.CONFIRMED, second.getState());
        assertEquals(setup.units() - setup.qty(),
                poolRepo.findById(setup.pool().getId()).orElseThrow().getAvailable());
    }

    @Test
    void concurrentDuplicateCallbacksSerializeToOneEffect() throws Exception {
        var setup = holdAndOrder();
        UUID orderPublicId = setup.order().getPublicId();

        int callers = 8;
        ExecutorService ex = Executors.newFixedThreadPool(callers);
        var barrier = new CyclicBarrier(callers);
        var futures = new java.util.ArrayList<Future<Order>>();
        for (int i = 0; i < callers; i++) {
            futures.add(ex.submit(() -> {
                barrier.await();
                return orderService.applyPaymentResult(orderPublicId, setup.userId(),
                        "ref-race-1", MockPaymentGateway.Outcome.SUCCESS);
            }));
        }
        ex.shutdown();
        assertTrue(ex.awaitTermination(60, TimeUnit.SECONDS));

        // All callers must see a legal state; inventory decremented exactly once.
        long confirmed = futures.stream().filter(f -> {
            try { return f.get().getState() == Order.State.CONFIRMED; } catch (Exception e) { return false; }
        }).count();
        assertTrue(confirmed >= 1, "at least one confirmation must succeed");
        assertEquals(setup.units() - setup.qty(),
                poolRepo.findById(setup.pool().getId()).orElseThrow().getAvailable(),
                "duplicate callbacks must not double-confirm or double-release");
    }

    @Test
    void orderAccessEnforcesOwnership() {
        var setup = holdAndOrder();
        assertThrows(com.synchros.common.DomainException.class,
                () -> orderService.getByPublicIdForUser(setup.order().getPublicId(), 999L),
                "user 999 must not read user 1's order");
    }

    @Test
    void lateCallbackAfterOrderExpiredIsAbsorbedNot500() {
        var setup = holdAndOrder();

        // Simulate the hold TTL lapsing before the gateway outcome arrives:
        // expire the reservation (releases inventory), which the expiration
        // path pairs with closing the open order.
        var sys = reservationService.getByPublicIdForUserForSystem(
                setup.reservation().getPublicId());
        assertTrue(reservationService.expireOne(sys));
        assertTrue(orderService.expireIfPending(setup.order().getId()));

        // The gateway outcome now arrives for a terminal order: must be
        // absorbed (no exception, no state change, no inventory mutation).
        Order result = assertDoesNotThrow(() -> orderService.applyPaymentResult(
                setup.order().getPublicId(), setup.userId(),
                "ref-late-1", MockPaymentGateway.Outcome.SUCCESS));

        assertEquals(Order.State.EXPIRED, result.getState(),
                "expired order must stay EXPIRED on late callback");
        assertEquals(setup.units(),
                poolRepo.findById(setup.pool().getId()).orElseThrow().getAvailable(),
                "late callback must not touch inventory (already released by expiry)");
    }

    // ---- fixture ----

    record Setup(InventoryPool pool, Reservation reservation, Order order,
                 Long userId, int units, int qty) {
    }

    private Setup holdAndOrder() {
        InventoryPool pool = seedPool(4);
        Long user = 1L;
        int qty = 1;
        Reservation r = reservationService.createReservationInternal(
                user, pool.getEventId(), pool.getId(), pool.getSection(), qty);
        Order order = orderService.createOrderForReservation(r.getPublicId(), user);
        return new Setup(pool, r, order, user, 4, qty);
    }

    private InventoryPool seedPool(int units) {
        try {
            var ctor = InventoryPool.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            InventoryPool p = ctor.newInstance();
            var f1 = InventoryPool.class.getDeclaredField("eventId");
            f1.setAccessible(true); f1.set(p, 1L);
            var f2 = InventoryPool.class.getDeclaredField("section");
            f2.setAccessible(true); f2.set(p, "PAY-" + UUID.randomUUID());
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
