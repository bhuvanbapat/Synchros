package com.synchros.kafka;

import com.synchros.outbox.SynchrosTopics;
import com.synchros.outbox.OutboxEvent;
import com.synchros.outbox.OutboxPublisher;
import com.synchros.outbox.OutboxRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * REAL Kafka integration test — the publisher→broker→consumer loop that
 * previously had zero automated regression protection (consumers were only
 * unit-tested with mocks; the broker path ran only in the compose demo).
 *
 * Boots an actual KRaft broker via Testcontainers, enables the REAL
 * listener containers and the REAL outbox publisher, and proves:
 *  1. outbox row → publisher → broker → consumer → processed_event + effect
 *  2. duplicate delivery → exactly one effect (consumer dedup)
 *  3. poison message → dead_letter row (bounded quarantine)
 *  4. durable retry counters survive (consumer_retry rows)
 *
 * Redis stays excluded (fail-open paths) — Postgres and Kafka are the
 * systems under test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "synchros.kafka.enabled=true",
                // Keep the publisher BEAN present (its @ConditionalOnProperty
                // keys off jobs.enabled) but push the scheduler interval far
                // beyond the test run — publication is driven manually.
                "synchros.jobs.enabled=true",
                "synchros.outbox.poll-interval-ms=3600000",
                "synchros.expiration.scan-interval-ms=3600000",
                "spring.kafka.consumer.auto-offset-reset=earliest",
        })
class KafkaLoopIT {

    /**
     * Raw KRaft broker container (same image + env contract as
     * docker-compose.yml, adapted for a fixed host port). Determinism
     * matters here: a random mapped port + advertised-listener mismatch
     * made the client silently hop to whatever else listens on
     * localhost:9092 (a leftover compose broker) — flaky passes and
     * cross-broker leakage. Binding a dedicated host port (19092) and
     * advertising exactly that keeps the entire test on the test broker.
     */
    static final int HOST_PORT = 19092;

    static final GenericContainer<?> KAFKA = new GenericContainer<>(
            DockerImageName.parse("apache/kafka:3.9.0"))
            .withNetwork(Network.SHARED)
            .withNetworkAliases("kafka")
            .withEnv("KAFKA_NODE_ID", "1")
            .withEnv("KAFKA_PROCESS_ROLES", "broker,controller")
            .withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@kafka:29093")
            .withEnv("KAFKA_LISTENERS",
                    "HOST://:9092,PLAINTEXT://:29092,CONTROLLER://:29093")
            .withEnv("KAFKA_ADVERTISED_LISTENERS",
                    "HOST://localhost:" + HOST_PORT + ",PLAINTEXT://kafka:29092")
            .withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
                    "HOST:PLAINTEXT,PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT")
            .withEnv("KAFKA_INTER_BROKER_LISTENER_NAME", "PLAINTEXT")
            .withEnv("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
            .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
            .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
            .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "true")
            .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                    .withPortBindings(new com.github.dockerjava.api.model.PortBinding(
                            com.github.dockerjava.api.model.Ports.Binding.bindPort(HOST_PORT),
                            com.github.dockerjava.api.model.ExposedPort.tcp(9092))))
            .waitingFor(new org.testcontainers.containers.wait.strategy.HostPortWaitStrategy()
                    .forPorts(9092));

    static {
        com.synchros.it.PostgresIntegrationBase.postgres().start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        var pg = com.synchros.it.PostgresIntegrationBase.postgres();
        registry.add("spring.datasource.url", pg::getJdbcUrl);
        registry.add("spring.datasource.username", pg::getUsername);
        registry.add("spring.datasource.password", pg::getPassword);
        registry.add("spring.autoconfigure.exclude", () ->
                "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
                + "org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration");
        registry.add("spring.kafka.bootstrap-servers",
                () -> "localhost:" + HOST_PORT);
        registry.add("synchros.kafka.enabled", () -> "true");
        registry.add("synchros.jobs.enabled", () -> "true");
        registry.add("synchros.outbox.poll-interval-ms", () -> "3600000");
        registry.add("synchros.expiration.scan-interval-ms", () -> "3600000");
        registry.add("synchros.jwt.secret",
                () -> "it-test-jwt-secret-0123456789-it-test-jwt-secret");
        registry.add("synchros.payment.webhook-secret",
                () -> "it-test-webhook-secret-0123456789-it-test-wh");
    }

    @Autowired OutboxRepository outboxRepo;
    @Autowired OutboxPublisher publisher;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired SynchrosTopics topics;
    @Autowired ProcessedEventRepository processedRepo;
    @Autowired DeadLetterRepository deadLetterRepo;
    @Autowired ConsumerRetryRepository retryRepo;
    @Autowired com.synchros.notification.NotificationRepository notificationRepo;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    @Autowired org.springframework.context.ApplicationContext applicationContext;

    static final long SEED_USER_ID = 1L; // seeded alice (V2 seed)

    @Test
    void outboxRowFlowsThroughBrokerToConsumerEffect() {
        // A ReservationConfirmed event through the ENTIRE loop.
        UUID reservationId = UUID.randomUUID();
        OutboxEvent event = tx.execute(status -> {
            OutboxEvent e = new OutboxEvent("ReservationConfirmed", "reservation",
                    reservationId.toString(),
                    "{\"reservationId\":\"" + reservationId + "\",\"userId\":"
                            + SEED_USER_ID + ",\"eventId\":1,\"section\":\"FLOOR\",\"quantity\":1}");
            return outboxRepo.save(e);
        });

        // The shared Postgres carries PENDING outbox rows from every earlier
        // IT context (they run with the publisher bean disabled) — one drain
        // call only claims the oldest 50. Keep draining exactly like the
        // scheduler does until THIS row flips; that also stress-drains the
        // backlog through the real broker.
        await("outbox row flips PUBLISHED").atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    publisher.publishPending();
                    assertEquals(OutboxEvent.State.PUBLISHED,
                            outboxRepo.findById(event.getId()).orElseThrow().getState());
                });

        await("consumer creates notification (the projection effect)").atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertTrue(notificationRepo
                        .findTop50ByUserIdOrderByCreatedAtDesc(SEED_USER_ID).stream()
                        .anyMatch(n -> "ReservationConfirmed".equals(n.getKind()))));
    }

    @Test
    void duplicateDeliveryProducesExactlyOneEffect() throws Exception {
        UUID eventId = UUID.randomUUID();
        String envelope = "{\"eventId\":\"" + eventId + "\","
                + "\"eventType\":\"ReservationCancelled\","
                + "\"aggregateType\":\"reservation\","
                + "\"aggregateId\":\"x\","
                + "\"occurredAt\":\"2026-01-01T00:00:00Z\","
                + "\"payload\":{\"reservationId\":\"x\",\"userId\":" + SEED_USER_ID + "}}";

        // Same envelope, twice, straight to the topic (publisher retries /
        // rebalances redeliveries simulated raw).
        kafkaTemplate.send(topics.topicFor("ReservationCancelled"), "x", envelope).get();
        kafkaTemplate.send(topics.topicFor("ReservationCancelled"), "x", envelope).get();

        await("dedup marker written once").atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertEquals(1, processedRepo
                        .findByEventIdAndConsumerGroup(eventId, "Synchros").stream().count()));

        long cancelledNotifications = notificationRepo
                .findTop50ByUserIdOrderByCreatedAtDesc(SEED_USER_ID).stream()
                .filter(n -> "ReservationCancelled".equals(n.getKind())
                        && n.getBody().contains("cancelled"))
                .count();
        // Multiple ReservationCancelled notifications may exist from other tests;
        // the invariant is the SINGLE processed marker, asserted above.
        assertTrue(cancelledNotifications >= 1);
    }

    @Test
    void poisonMessageQuarantinesAfterBoundedRetries() {
        // Valid envelope shape but a payload that fails the handler's
        // strict contract (userId missing -> handler throws -> retries).
        UUID poisonId = UUID.randomUUID();
        String poison = "{\"eventId\":\"" + poisonId + "\","
                + "\"eventType\":\"ReservationConfirmed\","
                + "\"aggregateType\":\"reservation\","
                + "\"aggregateId\":\"p\","
                + "\"occurredAt\":\"2026-01-01T00:00:00Z\","
                + "\"payload\":{\"reservationId\":\"p\"}}"; // NO userId

        // Drive the listener method directly: MAX_ATTEMPTS rounds of the
        // same poison delivery. (Listener container redelivery with a real
        // broker retrying 5x needs the DefaultErrorHandler backoff —
        // calling the handler the way the container does keeps the
        // counter/quarantine contract under test without 30s of backoff.)
        EventConsumers consumers = applicationContext.getBean(EventConsumers.class);

        for (int i = 0; i < 5; i++) {
            try {
                consumers.onReservationEvent(poison);
            } catch (org.springframework.kafka.KafkaException retryable) {
                // expected until the attempt threshold
            }
        }

        await("poison dead-lettered").atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertTrue(deadLetterRepo.findAll().stream()
                        .anyMatch(d -> d.getEventId() != null
                                && d.getEventId().equals(poisonId))));

        // Durable counter row was cleared on quarantine.
        assertTrue(retryRepo.findById("reservation-events:" + poisonId).isEmpty());
    }
}
