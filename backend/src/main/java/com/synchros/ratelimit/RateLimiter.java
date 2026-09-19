package com.synchros.ratelimit;

/**
 * Per-user, per-endpoint-class rate limiting contract.
 * Implementation is Redis-based (token bucket via Lua for atomicity).
 * Redis being unavailable must NOT break the reservation path — the
 * implementation fails OPEN and counts on idempotency + DB constraints
 * for correctness (rate limiting is abuse protection, not a consistency
 * mechanism — documented in docs/SECURITY.md).
 */
public interface RateLimiter {

    /**
     * @return true if the request is allowed; false means limit exceeded.
     */
    boolean tryAcquire(String endpointClass, Long userId);
}
