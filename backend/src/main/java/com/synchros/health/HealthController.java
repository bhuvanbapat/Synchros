package com.synchros.health;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** App-level health combining DB + Redis reachability. */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final JdbcTemplate jdbc;
    private final ObjectProvider<StringRedisTemplate> redisProvider;

    public HealthController(JdbcTemplate jdbc,
                            ObjectProvider<StringRedisTemplate> redisProvider) {
        this.jdbc = jdbc;
        this.redisProvider = redisProvider;
    }

    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "UP");
        out.put("database", checkDb());
        out.put("redis", checkRedis());
        return out;
    }

    private String checkDb() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return "UP";
        } catch (Exception e) {
            return "DOWN";
        }
    }

    private String checkRedis() {
        var redis = redisProvider.getIfAvailable();
        if (redis == null) return "DISABLED";
        var connection = redis.getConnectionFactory().getConnection();
        try {
            connection.ping();
            return "UP";
        } catch (Exception e) {
            return "DOWN";
        } finally {
            // Return the borrowed connection to the pool — never close() a
            // pooled connection directly, and never leave it out: a health
            // endpoint must not cause the outage it watches.
            org.springframework.data.redis.core.RedisConnectionUtils
                    .releaseConnection(connection, redis.getConnectionFactory());
        }
    }
}
