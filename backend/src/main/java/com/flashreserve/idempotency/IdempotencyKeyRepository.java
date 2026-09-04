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

    @Modifying
    @Query("DELETE FROM IdempotencyKey k WHERE k.expiresAt < :cutoff")
    int purgeExpired(@Param("cutoff") Instant cutoff);
}
