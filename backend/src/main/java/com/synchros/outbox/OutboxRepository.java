package com.synchros.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Publisher claim: pending or retryable failures whose lease is free
     * (or expired), oldest first, FOR UPDATE SKIP LOCKED inside the
     * caller's transaction. Two publisher instances each get a DISJOINT
     * batch — no duplicate sends, no lock convoy. The lease predicate
     * lives IN SQL (not post-filter in Java): a batch of 50 leased rows
     * can never starve the poll into returning nothing while work exists.
     * Native query because JPQL has no SKIP LOCKED.
     */
    @Query(value = "SELECT * FROM outbox_event " +
           "WHERE state IN ('PENDING','FAILED') " +
           "AND (leased_until IS NULL OR leased_until <= now()) " +
           "ORDER BY created_at ASC " +
           "LIMIT :max FOR UPDATE SKIP LOCKED",
           nativeQuery = true)
    List<OutboxEvent> findPublishable(@Param("max") int max);

    List<OutboxEvent> findByStateOrderByCreatedAtDesc(OutboxEvent.State state);

    long countByState(OutboxEvent.State state);

    /** Retention: drop published rows older than the cutoff. */
    @Modifying
    @Query("DELETE FROM OutboxEvent o WHERE o.state = 'PUBLISHED' AND o.publishedAt < :cutoff")
    int purgePublishedBefore(@Param("cutoff") Instant cutoff);
}
