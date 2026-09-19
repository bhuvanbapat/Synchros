package com.synchros.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis token-bucket rate limiter implemented with an atomic Lua script.
 *
 * Bucket state per (endpointClass, userId): {tokens, lastRefillTimestamp}.
 * The script refills lazily and consumes one token atomically, so concurrent
 * requests from the same user cannot race past the limit.
 *
 * Failure mode: Redis unavailable OR NOT CONFIGURED (tests) => FAIL OPEN
 * (allow). Rationale: the reservation path's correctness is protected by DB
 * constraints and idempotency; rate limiting is defense against abuse
 * volume, and failing closed would make Redis a single point of failure
 * for the whole API.
 */
@Component
public class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    private static final String LUA = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refillPerSecond = tonumber(ARGV[2])
            local nowMillis = tonumber(ARGV[3])
            local state = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(state[1])
            local ts = tonumber(state[2])
            if tokens == nil then
                tokens = capacity
                ts = nowMillis
            end
            local elapsedSeconds = math.max(0, (nowMillis - ts) / 1000.0)
            tokens = math.min(capacity, tokens + elapsedSeconds * refillPerSecond)
            if tokens < 1 then
                redis.call('HMSET', key, 'tokens', tokens, 'ts', nowMillis)
                redis.call('EXPIRE', key, math.ceil(capacity / refillPerSecond) * 2)
                return 0
            end
            tokens = tokens - 1
            redis.call('HMSET', key, 'tokens', tokens, 'ts', nowMillis)
            redis.call('EXPIRE', key, math.ceil(capacity / refillPerSecond) * 2)
            return 1
            """;

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final Counter allowed;
    private final Counter rejected;
    private final double permitsPerSecond;
    private final int burst;

    public RedisRateLimiter(ObjectProvider<StringRedisTemplate> redisProvider,
                            MeterRegistry meters,
                            com.synchros.config.SynchrosProperties props) {
        this.redisProvider = redisProvider;
        this.permitsPerSecond = props.getRateLimit().getReservationsPerMinute() / 60.0;
        this.burst = props.getRateLimit().getBurst();
        this.allowed = Counter.builder("Synchros_rate_limit")
                .tag("outcome", "allowed").register(meters);
        this.rejected = Counter.builder("Synchros_rate_limit")
                .tag("outcome", "rejected").register(meters);
    }

    @Override
    public boolean tryAcquire(String endpointClass, Long userId) {
        var redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return true; // fail-open when Redis is absent
        }
        String key = "rl:" + endpointClass + ":" + userId;
        try {
            Long result = redis.execute(
                    org.springframework.data.redis.core.script.RedisScript.of(LUA, Long.class),
                    List.of(key),
                    String.valueOf(burst),
                    String.valueOf(permitsPerSecond),
                    String.valueOf(System.currentTimeMillis()));
            boolean ok = result != null && result == 1L;
            (ok ? allowed : rejected).increment();
            return ok;
        } catch (Exception e) {
            log.warn("rate limiter unavailable (fail-open): {}", e.getMessage());
            return true;
        }
    }
}
