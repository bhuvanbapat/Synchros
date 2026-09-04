package com.flashreserve.outbox;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /** Publisher poll: pending or retryable failures, oldest first. */
    @Query("SELECT o FROM OutboxEvent o WHERE o.state IN ('PENDING','FAILED') " +
           "ORDER BY o.createdAt ASC")
    List<OutboxEvent> findPublishable(Limit limit);

    List<OutboxEvent> findByStateOrderByCreatedAtDesc(OutboxEvent.State state);

    long countByState(OutboxEvent.State state);

    @Modifying
    @Query("DELETE FROM OutboxEvent o WHERE o.state = 'PUBLISHED' AND o.publishedAt < :cutoff")
    int purgePublishedBefore(@Param("cutoff") Instant cutoff);

    @Modifying
    @Query("DELETE FROM IdempotencyKey k WHERE k.expiresAt < :cutoff")
    int purgeExpiredIdempotencyKeys(@Param("cutoff") Instant cutoff);

    interface IdempotencyKey {
    }
}
