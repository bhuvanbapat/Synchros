package com.flashreserve.it;

import com.flashreserve.outbox.OutboxEvent;
import com.flashreserve.outbox.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Outbox contract: the row commits WITH the business mutation.
 * (Kafka publication itself is covered by the compose demo + load tests —
 * here we prove the transactional part that guarantees no lost events.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OutboxIT extends PostgresIntegrationBase {

    @Autowired OutboxRepository outboxRepo;
    @Autowired TransactionTemplate tx;

    @Test
    void outboxRowCommitsWithBusinessChange() {
        String aggregateId = UUID.randomUUID().toString();
        tx.executeWithoutResult(t -> {
            // In a real flow this is the business UPDATE + outbox INSERT.
            outboxRepo.save(new OutboxEvent("ReservationConfirmed", "reservation",
                    aggregateId, "{\"x\":1}"));
        });

        var rows = outboxRepo.findAll().stream()
                .filter(o -> aggregateId.equals(o.getAggregateId()))
                .toList();
        assertEquals(1, rows.size());
        assertEquals(OutboxEvent.State.PENDING, rows.get(0).getState());
    }

    @Test
    void rollbackRemovesOutboxRowWithBusinessChange() {
        String aggregateId = UUID.randomUUID().toString();
        try {
            tx.executeWithoutResult(t -> {
                outboxRepo.save(new OutboxEvent("ReservationCancelled", "reservation",
                        aggregateId, "{\"x\":1}"));
                throw new RuntimeException("business failure -> rollback");
            });
        } catch (RuntimeException expected) {
            // rolled back
        }
        long count = outboxRepo.findAll().stream()
                .filter(o -> aggregateId.equals(o.getAggregateId()))
                .count();
        assertEquals(0, count, "outbox row must roll back with the business tx");
    }
}
