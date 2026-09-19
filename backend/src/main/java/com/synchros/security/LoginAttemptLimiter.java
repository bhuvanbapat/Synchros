package com.Synchros.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Brute-force protection for the credential exchange: a fixed-window
 * attempt counter per email (optionally IP-scoped) in Redis with a
 * lockout period. Deliberately NOT a token bucket: login attempts should
 * be cheap when legitimate and *stopped* when hostile — 5 failures lock
 * the key for the window, regardless of pacing.
 *
 * Failure mode: Redis unavailable => FAIL OPEN (allow). Rationale mirrors
 * RedisRateLimiter: correctness is never at stake in an auth limiter,
 * but availability of login must not depend on a cache; the window is
 * short and the real credential check still runs.
 */
@Component
public class LoginAttemptLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptLimiter.class);

    static final int MAX_ATTEMPTS = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final String KEY_PREFIX = "authlock:";

    private final ObjectProvider<StringRedisTemplate> redisProvider;

    public LoginAttemptLimiter(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redisProvider = redisProvider;
    }

    /** @return true if the email (optionally +IP) may attempt a login now. */
    public boolean isAllowed(String email, String remoteAddr) {
        var redis = redisProvider.getIfAvailable();
        if (redis == null) return true;
        try {
            String count = redis.opsForValue().get(key(email, remoteAddr));
            return count == null || Integer.parseInt(count) < MAX_ATTEMPTS;
        } catch (Exception e) {
            log.warn("login limiter unavailable (fail-open): {}", e.getMessage());
            return true;
        }
    }

    /** Records a failed attempt; crossing the threshold locks the window. */
    public void recordFailure(String email, String remoteAddr) {
        var redis = redisProvider.getIfAvailable();
        if (redis == null) return;
        try {
            String key = key(email, remoteAddr);
            Long attempts = redis.opsForValue().increment(key);
            if (attempts != null && attempts == 1L) {
                redis.expire(key, WINDOW);
            }
        } catch (Exception e) {
            log.warn("login limiter unavailable (fail-open): {}", e.getMessage());
        }
    }

    /** Clears the counter on success — lockout never outlives good luck. */
    public void recordSuccess(String email, String remoteAddr) {
        var redis = redisProvider.getIfAvailable();
        if (redis == null) return;
        try {
            redis.delete(key(email, remoteAddr));
        } catch (Exception e) {
            log.warn("login limiter unavailable (fail-open): {}", e.getMessage());
        }
    }

    private static String key(String email, String remoteAddr) {
        // Email normalized to lowercase so case tricks don't reset counters.
        String local = email == null ? "?" : email.trim().toLowerCase();
        String ip = remoteAddr == null ? "unknown" : remoteAddr;
        return KEY_PREFIX + local + ":" + ip;
    }

}
