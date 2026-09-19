package com.synchros.redis;

import com.synchros.cache.CatalogCache;
import com.synchros.security.LoginAttemptLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REAL Redis integration test. The Lua token bucket's atomicity, the
 * cache-aside TTL contract, and the login lockout counter were previously
 * argued, not asserted (all other ITs exclude Redis — a typo in the Lua
 * would have shipped green). This boots a real Redis and pins all three.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "synchros.jobs.enabled=false",
                "synchros.kafka.enabled=false",
                // Tiny bucket so the boundary is reachable in one test.
                "synchros.rate-limit.reservations-per-minute=12",
                "synchros.rate-limit.burst=3",
        })
class RedisRateLimitCacheIT {

    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static {
        com.synchros.it.PostgresIntegrationBase.postgres().start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        var pg = com.synchros.it.PostgresIntegrationBase.postgres();
        registry.add("spring.datasource.url", pg::getJdbcUrl);
        registry.add("spring.datasource.username", pg::getUsername);
        registry.add("spring.datasource.password", pg::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.autoconfigure.exclude", () ->
                "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration");
        registry.add("synchros.jobs.enabled", () -> "false");
        registry.add("synchros.kafka.enabled", () -> "false");
        registry.add("synchros.jwt.secret",
                () -> "it-test-jwt-secret-0123456789-it-test-jwt-secret");
        registry.add("synchros.payment.webhook-secret",
                () -> "it-test-webhook-secret-0123456789-it-test-wh");
    }

    @Autowired com.synchros.ratelimit.RedisRateLimiter limiter;
    @Autowired CatalogCache cache;
    @Autowired LoginAttemptLimiter loginLimiter;
    @Autowired StringRedisTemplate redis;

    @Test
    void tokenBucketEnforcesBurstThenRejects() {
        // Unique user per run so the test never inherits bucket state.
        long user = System.nanoTime();

        // Burst capacity 3: first 3 acquire, then rejected until refill.
        assertTrue(limiter.tryAcquire("reservations", user), "burst token 1");
        assertTrue(limiter.tryAcquire("reservations", user), "burst token 2");
        assertTrue(limiter.tryAcquire("reservations", user), "burst token 3");
        assertFalse(limiter.tryAcquire("reservations", user), "bucket empty -> reject");
        assertFalse(limiter.tryAcquire("reservations", user), "still empty -> reject");

        // Refill: 12/min = 0.2 tokens/s. Wait 6s -> ~1.2 tokens refilled;
        // one acquire must succeed again.
        try {
            Thread.sleep(6_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertTrue(limiter.tryAcquire("reservations", user),
                "lazy refill must restore tokens after waiting");
    }

    @Test
    void concurrentAcquiresNeverExceedBurstAtomic() throws Exception {
        // 16 concurrent acquires against a fresh bucket of 3: exactly 3
        // succeed, 13 reject — the Lua script is atomic or this fails.
        long user = System.nanoTime() + 1;
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            java.util.List<Future<Boolean>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return limiter.tryAcquire("reservations", user);
                }));
            }
            start.countDown();
            long allowed = 0;
            for (Future<Boolean> f : futures) {
                if (f.get(10, TimeUnit.SECONDS)) allowed++;
            }
            assertEquals(3, allowed, "exactly burst=3 acquires must win, concurrently");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cacheAsideRoundTripsWithTtl() {
        long poolId = 42L + System.nanoTime();

        assertTrue(cache.getAvailability(poolId).isEmpty(), "miss on first read");
        cache.putAvailability(poolId, "17");
        assertEquals(Optional.of("17"), cache.getAvailability(poolId), "hit after write");

        // Raw TTL check: the key exists and carries a positive TTL.
        Long ttl = redis.getExpire("cache:avail:" + poolId);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 3, "availability hint TTL is ~3s, got " + ttl);
    }

    @Test
    void loginLockoutCountsAndClears() {
        String email = "lockout-" + System.nanoTime() + "@example.com";
        String ip = "10.0.0.9";

        assertTrue(loginLimiter.isAllowed(email, ip));
        for (int i = 0; i < 5; i++) {
            loginLimiter.recordFailure(email, ip);
        }
        assertFalse(loginLimiter.isAllowed(email, ip),
                "5 failures must lock the (email, ip) window");

        // Different IP, same email: independent counter (scoped key).
        assertTrue(loginLimiter.isAllowed(email, "10.0.0.10"));

        // Success clears the counter — lockout never outlives good luck.
        loginLimiter.recordSuccess(email, ip);
        assertTrue(loginLimiter.isAllowed(email, ip));
    }
}
