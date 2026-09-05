package com.flashreserve.it;

import com.flashreserve.idempotency.IdempotencyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for the concurrent-claim 500:
 *
 * N threads race begin(key) for the SAME (user, operation, key).
 * Contract: exactly ONE Fresh; every other thread gets InFlight — and
 * critically, NO thread may see an exception. The old implementation
 * caught DataIntegrityViolationException around saveAndFlush, but the
 * flushed constraint violation had already marked the REQUIRES_NEW tx
 * rollback-only: the "graceful" InFlight path then died at commit with
 * UnexpectedRollbackException -> 500 on every concurrent duplicate.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ConcurrentIdempotencyClaimIT extends PostgresIntegrationBase {

    @Autowired IdempotencyService idempotencyService;

    @Test
    void concurrentClaimsYieldOneFreshAndNoExceptions() throws Exception {
        Long user = 3L; // bob — distinct from other ITs' users
        String key = "concurrent-claim-" + java.util.UUID.randomUUID();

        int threads = 16;
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        var barrier = new CyclicBarrier(threads);
        var fresh = new AtomicInteger();
        var inFlight = new AtomicInteger();
        var exceptions = new AtomicInteger();

        var futures = new java.util.ArrayList<Future<?>>();
        for (int i = 0; i < threads; i++) {
            futures.add(ex.submit(() -> {
                try {
                    barrier.await();
                } catch (InterruptedException | BrokenBarrierException e) {
                    throw new RuntimeException(e);
                }
                try {
                    switch (idempotencyService.begin(user, "CREATE_RESERVATION", key, "h")) {
                        case IdempotencyService.Fresh f -> fresh.incrementAndGet();
                        case IdempotencyService.InFlight inf -> inFlight.incrementAndGet();
                        case IdempotencyService.Replay r -> inFlight.incrementAndGet();
                        default -> { }
                    }
                } catch (Exception e) {
                    exceptions.incrementAndGet();
                    throw new RuntimeException(e);
                }
            }));
        }
        ex.shutdown();
        assertTrue(ex.awaitTermination(60, TimeUnit.SECONDS));

        assertEquals(0, exceptions.get(), "no thread may throw — the old "
                + "rollback-only bug surfaced here as 500s");
        assertEquals(1, fresh.get(), "exactly one claim wins");
        assertEquals(threads - 1, inFlight.get(),
                "all losers get the clean InFlight response");
    }
}
