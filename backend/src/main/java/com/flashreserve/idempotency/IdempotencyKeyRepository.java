package com.flashreserve.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    Optional<IdempotencyKey> findByUserIdAndOperationAndIdemKey(Long userId,
                                                                 String operation,
                                                                 String idemKey);

    /**
     * Atomic claim of an idempotency key: returns 1 when this caller claimed
     * it, 0 when a concurrent request holds it.
     *
     * Implemented as INSERT ... ON CONFLICT DO NOTHING deliberately: the
     * exception-driven alternative (catch DataIntegrityViolationException
     * from saveAndFlush) does NOT work — the constraint violation during
     * Hibernate flush marks the transaction rollback-only before application
     * code can catch it, so the "graceful" path dies at commit with
     * UnexpectedRollbackException (observed as 500s under concurrent
     * duplicate keys). Rowcount semantics avoid poisoning the transaction.
     */
    @Modifying
    @Query(value = "INSERT INTO idempotency_key " +
            "(idem_key, user_id, operation, request_hash, state, created_at, expires_at) " +
            "VALUES (:key, :userId, :operation, :requestHash, 'IN_FLIGHT', now(), :expiresAt) " +
            "ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int tryClaim(@Param("key") String key,
                 @Param("userId") Long userId,
                 @Param("operation") String operation,
                 @Param("requestHash") String requestHash,
                 @Param("expiresAt") Instant expiresAt);

    @Modifying
    @Query("DELETE FROM IdempotencyKey k WHERE k.expiresAt < :cutoff")
    int purgeExpired(@Param("cutoff") Instant cutoff);
}
