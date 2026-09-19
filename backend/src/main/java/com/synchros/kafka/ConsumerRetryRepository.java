package com.synchros.kafka;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Durable consumer retry counters (V6): the attempt count for a failing
 * message survives restarts, so bounded retry is bounded *globally*, not
 * "bounded per uptime".
 */
public interface ConsumerRetryRepository
        extends JpaRepository<ConsumerRetry, String> {

    @Modifying
    @Query("DELETE FROM ConsumerRetry c WHERE c.dedupKey = :k")
    void clear(@Param("k") String dedupKey);

    /** Retention: drop counters older than the cutoff (stale failures). */
    @Modifying
    @Query("DELETE FROM ConsumerRetry c WHERE c.updatedAt < :cutoff")
    int purgeOlderThan(@Param("cutoff") java.time.Instant cutoff);
}
