package com.Synchros.it;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * ONE shared Postgres container for the entire suite (JVM singleton).
 * Each test class still gets a clean logical state via unique section
 * names / UUIDs rather than container restarts — integration tests here
 * are additive and self-verifying (reconciliation tests reconcile the
 * world they seed), so cross-class data sharing is safe and 10x faster
 * than per-class containers on resource-constrained Docker hosts.
 */
public abstract class PostgresIntegrationBase {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("Synchros_test")
                    .withUsername("Synchros")
                    .withPassword("Synchros_local_dev")
                    .withReuse(true)
                    .withCommand("postgres", "-c", "max_connections=200",
                            "-c", "shared_buffers=64MB");

    static {
        POSTGRES.start();
    }

    /** Cross-package accessor: the Kafka/Redis ITs share this one container. */
    public static PostgreSQLContainer<?> postgres() {
        return POSTGRES;
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration,"
                    + "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
                    + "org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration");
        registry.add("Synchros.kafka.enabled", () -> "false");
        // Tests drive expiration/outbox logic synchronously; disable the
        // background jobs so they never race with assertions.
        registry.add("Synchros.jobs.enabled", () -> "false");
        registry.add("Synchros.jwt.secret",
                () -> "it-test-jwt-secret-0123456789-it-test-jwt-secret");
        registry.add("Synchros.payment.webhook-secret",
                () -> "it-test-webhook-secret-0123456789-it-test-wh");
        registry.add("spring.flyway.clean-disabled", () -> "false");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "12");
    }
}
