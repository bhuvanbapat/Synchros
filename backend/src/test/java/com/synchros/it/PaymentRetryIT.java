package com.synchros.it;

import com.synchros.common.DomainException;
import com.synchros.inventory.InventoryPool;
import com.synchros.inventory.InventoryPoolRepository;
import com.synchros.order.Order;
import com.synchros.order.OrderService;
import com.synchros.payment.MockPaymentGateway;
import com.synchros.reservation.Reservation;
import com.synchros.reservation.ReservationService;
import com.synchros.user.AuthDtos;
import com.synchros.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the payment-timeout brick: a TIMEOUT must leave the
 * payment retryable so a subsequent SUCCESS (late webhook or client retry)
 * still confirms the order — previously the payment was terminal TIMED_OUT
 * and every retry 409'd forever.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PaymentRetryIT extends PostgresIntegrationBase {

    @Autowired ReservationService reservationService;
    @Autowired InventoryPoolRepository poolRepo;
    @Autowired OrderService orderService;
    @Autowired UserService userService;

    private Order newHoldWithOrder() throws Exception {
        InventoryPool pool = seedPool();
        Reservation r = reservationService.createReservationInternal(
                1L, pool.getEventId(), pool.getId(), pool.getSection(), 1);
        return orderService.createOrderForReservation(r.getPublicId(), 1L);
    }

    private InventoryPool seedPool() throws Exception {
        var ctor = InventoryPool.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        InventoryPool p = ctor.newInstance();
        var f1 = InventoryPool.class.getDeclaredField("eventId");
        f1.setAccessible(true); f1.set(p, 1L);
        var f2 = InventoryPool.class.getDeclaredField("section");
        f2.setAccessible(true); f2.set(p, "RETRY-" + java.util.UUID.randomUUID());
        var f3 = InventoryPool.class.getDeclaredField("total");
        f3.setAccessible(true); f3.set(p, 2);
        var f4 = InventoryPool.class.getDeclaredField("available");
        f4.setAccessible(true); f4.set(p, 2);
        return poolRepo.save(p);
    }

    @Test
    void timeoutThenSuccessStillConfirms() throws Exception {
        Order order = newHoldWithOrder();

        // Gateway timeout: attempt recorded, payment left retryable.
        Order afterTimeout = orderService.applyPaymentResult(
                order.getPublicId(), 1L, "ref-to-1",
                MockPaymentGateway.Outcome.TIMEOUT);
        assertEquals(Order.State.PENDING_PAYMENT, afterTimeout.getState(),
                "timeout must NOT terminal-fail the order");

        // The definitive outcome arrives (late webhook / retry): SUCCESS.
        Order afterSuccess = orderService.applyPaymentResult(
                order.getPublicId(), 1L, "ref-to-1",
                MockPaymentGateway.Outcome.SUCCESS);
        assertEquals(Order.State.CONFIRMED, afterSuccess.getState(),
                "a success after a timeout must confirm — the retry path was bricked before");
    }

    @Test
    void repeatedTimeoutsAreAllRecordedButNeverTerminal() throws Exception {
        Order order = newHoldWithOrder();

        for (int i = 1; i <= 3; i++) {
            Order result = orderService.applyPaymentResult(
                    order.getPublicId(), 1L, "ref-to-" + i,
                    MockPaymentGateway.Outcome.TIMEOUT);
            assertEquals(Order.State.PENDING_PAYMENT, result.getState());
        }

        Order finalResult = orderService.applyPaymentResult(
                order.getPublicId(), 1L, "ref-final",
                MockPaymentGateway.Outcome.SUCCESS);
        assertEquals(Order.State.CONFIRMED, finalResult.getState());
    }

    @Test
    void concurrentDuplicateEmailRegistrationsYieldOneClean400() throws Exception {
        String email = "race-" + java.util.UUID.randomUUID() + "@example.com";
        var request = new AuthDtos.RegisterRequest(email, "password123");

        int callers = 8;
        ExecutorService ex = Executors.newFixedThreadPool(callers);
        var barrier = new CyclicBarrier(callers);
        AtomicInteger cleanRejects = new AtomicInteger();
        AtomicInteger created = new AtomicInteger();
        var futures = new java.util.ArrayList<Future<Boolean>>();
        for (int i = 0; i < callers; i++) {
            futures.add(ex.submit(() -> {
                barrier.await();
                try {
                    userService.register(request);
                    created.incrementAndGet();
                    return true;
                } catch (DomainException e) {
                    // Must be the clean INVALID_REQUEST 400-mapped rejection,
                    // never a raw DataIntegrityViolationException (500).
                    assertEquals(DomainException.ErrorCode.INVALID_REQUEST, e.getCode());
                    cleanRejects.incrementAndGet();
                    return false;
                }
            }));
        }
        ex.shutdown();
        assertTrue(ex.awaitTermination(60, TimeUnit.SECONDS));

        assertEquals(1, created.get(), "exactly one register must win");
        assertEquals(callers - 1, cleanRejects.get(),
                "losers must get the clean domain rejection");
    }
}
