package com.flashreserve.idempotency;

import tools.jackson.databind.ObjectMapper;
import com.flashreserve.common.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * DB-backed idempotency for mutations.
 *
 * Semantics:
 *  - Scope: (userId, operation, key). Keys are per-user, per-operation.
 *  - First request with a key: IN_FLIGHT row created in its own transaction
 *    (REQUIRES_NEW) *before* the business transaction, so a crash mid-flight
 *    leaves a record; the UNIQUE constraint makes concurrent duplicate
 *    inserts collide — the loser gets a conflict and either waits (retries)
 *    or rejects.
 *  - Same key + same request fingerprint + COMPLETED: cached response
 *    replayed (no duplicate business effect).
 *  - Same key + different fingerprint: 409 IDEMPOTENCY_CONFLICT (client bug
 *    or malicious key reuse).
 *  - IN_FLIGHT duplicates: 409 asking the client to retry later (the original
 *    request is still executing).
 *  - Keys expire (24h default) and are purged by a scheduled job.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final Duration TTL = Duration.ofHours(24);

    public static final String HEADER = "Idempotency-Key";

    private final IdempotencyKeyRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyKeyRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** SHA-256 fingerprint of the canonical request body. */
    public String fingerprint(Object requestBody) {
        try {
            String canonical = objectMapper.writeValueAsString(requestBody);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot fingerprint request", e);
        }
    }

    /**
     * Result of the pre-check performed before executing the operation.
     */
    public sealed interface Precheck permits Replay, InFlight, Fresh {
    }

    /** Cached response exists — replay it. */
    public record Replay(int status, String bodyJson) implements Precheck {
    }

    /** Original request still executing — tell client to retry later. */
    public record InFlight() implements Precheck {
    }

    /** No record — safe to execute; guard row was claimed. */
    public record Fresh(IdempotencyKey claimed) implements Precheck {
    }

    /**
     * Claims the idempotency key. MUST be called outside (before) the business
     * transaction: the claim itself is REQUIRES_NEW.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Precheck begin(Long userId, String operation, String key, String requestHash) {
        var existing = repository.findByUserIdAndOperationAndIdemKey(userId, operation, key);
        if (existing.isPresent()) {
            IdempotencyKey row = existing.get();
            if (row.getExpiresAt().isBefore(Instant.now())) {
                repository.delete(row);
                repository.flush();
            } else {
                if (!row.getRequestHash().equals(requestHash)) {
                    throw new DomainException(
                            DomainException.ErrorCode.IDEMPOTENCY_CONFLICT,
                            "Idempotency key already used with a different request body");
                }
                if ("COMPLETED".equals(row.getState())) {
                    return new Replay(row.getResponseStatus(), row.getResponseBody());
                }
                return new InFlight();
            }
        }
        IdempotencyKey claimed = new IdempotencyKey(key, userId, operation,
                requestHash, Instant.now().plus(TTL));
        try {
            repository.saveAndFlush(claimed);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Concurrent duplicate insert collided on the UNIQUE constraint.
            return new InFlight();
        }
        return new Fresh(claimed);
    }

    /**
     * Persist the response for later replays. REQUIRES_NEW: it must commit
     * even if the surrounding business transaction already committed (it is
     * called after commit in the controller helper).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(IdempotencyKey claimed, int status, Object body) {
        String bodyJson;
        try {
            bodyJson = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            bodyJson = "{}";
        }
        claimed.complete(status, bodyJson);
        repository.save(claimed);
    }

    /** Release an IN_FLIGHT claim on validation failure (nothing executed). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(IdempotencyKey claimed) {
        repository.delete(claimed);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purgeExpired() {
        int purged = repository.purgeExpired(Instant.now());
        if (purged > 0) {
            log.info("purged {} expired idempotency keys", purged);
        }
        return purged;
    }
}
