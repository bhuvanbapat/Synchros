package com.Synchros.cache;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Cache-aside wrapper for availability reads. Rules (docs/ARCHITECTURE.md):
 *  - Pool availability is cached with a SHORT TTL purely as a display hint;
 *    the reservation path never trusts it — the conditional UPDATE is the
 *    gate. A stale hint can cause a pointless 409, never an oversell.
 *  - Redis down OR NOT PRESENT (tests, minimal profiles) => cache is
 *    bypassed entirely (fail-open read-through to Postgres).
 */
@Service
public class CatalogCache {

    private static final Logger log = LoggerFactory.getLogger(CatalogCache.class);

    private static final Duration AVAILABILITY_TTL = Duration.ofSeconds(3);

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final MeterRegistry meters;

    public CatalogCache(ObjectProvider<StringRedisTemplate> redisProvider,
                        MeterRegistry meters) {
        this.redisProvider = redisProvider;
        this.meters = meters;
    }

    private StringRedisTemplate redis() {
        return redisProvider.getIfAvailable();
    }

    public Optional<String> getAvailability(Long poolId) {
        return read("cache:avail:" + poolId, "availability");
    }

    public void putAvailability(Long poolId, String value) {
        write("cache:avail:" + poolId, value, AVAILABILITY_TTL);
    }

    private Optional<String> read(String key, String cacheName) {
        var redis = redis();
        if (redis == null) return Optional.empty();
        try {
            String value = redis.opsForValue().get(key);
            if (value != null) {
                meters.counter("Synchros_cache", "name", cacheName,
                        "outcome", "hit").increment();
                return Optional.of(value);
            }
            meters.counter("Synchros_cache", "name", cacheName,
                    "outcome", "miss").increment();
        } catch (Exception e) {
            meters.counter("Synchros_cache", "name", cacheName,
                    "outcome", "error").increment();
            log.warn("cache read failed (fail-open): {}", e.getMessage());
        }
        return Optional.empty();
    }

    private void write(String key, String value, Duration ttl) {
        var redis = redis();
        if (redis == null) return;
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            log.warn("cache write failed (ignored): {}", e.getMessage());
        }
    }
}
