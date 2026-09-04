package com.flashreserve.it;

import com.flashreserve.idempotency.IdempotencyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class IdempotencyIT extends PostgresIntegrationBase {

    @Autowired IdempotencyService idempotencyService;

    @Test
    void sameKeySameRequestReplaysOnce() {
        Long user = 1L;
        String key = "it-key-1";
        String hash = "abc123hash";

        // First call: Fresh
        var first = idempotencyService.begin(user, "CREATE_RESERVATION", key, hash);
        assertInstanceOf(IdempotencyService.Fresh.class, first);

        // Complete it
        idempotencyService.complete(((IdempotencyService.Fresh) first).claimed(),
                201, java.util.Map.of("id", "r-1"));

        // Second call, same key + hash: Replay
        var second = idempotencyService.begin(user, "CREATE_RESERVATION", key, hash);
        assertInstanceOf(IdempotencyService.Replay.class, second);
        assertEquals(201, ((IdempotencyService.Replay) second).status());
    }

    @Test
    void sameKeyDifferentRequestConflicts() {
        Long user = 1L;
        String key = "it-key-2";
        var first = idempotencyService.begin(user, "CREATE_RESERVATION", key, "hash-a");
        idempotencyService.complete(((IdempotencyService.Fresh) first).claimed(), 201,
                java.util.Map.of());

        assertThrows(com.flashreserve.common.DomainException.class,
                () -> idempotencyService.begin(user, "CREATE_RESERVATION", key, "hash-b"),
                "different body with same key must conflict");
    }

    @Test
    void differentKeysSameRequestBothExecute() {
        Long user = 1L;
        var a = idempotencyService.begin(user, "CREATE_RESERVATION", "key-x-1", "same-hash");
        var b = idempotencyService.begin(user, "CREATE_RESERVATION", "key-x-2", "same-hash");
        assertInstanceOf(IdempotencyService.Fresh.class, a);
        assertInstanceOf(IdempotencyService.Fresh.class, b);
    }

    @Test
    void inFlightKeyReturnsInFlight() {
        Long user = 2L;
        String key = "it-key-3";
        var first = idempotencyService.begin(user, "CREATE_RESERVATION", key, "h");
        assertInstanceOf(IdempotencyService.Fresh.class, first);
        // not completed -> concurrent duplicate sees IN_FLIGHT
        var dup = idempotencyService.begin(user, "CREATE_RESERVATION", key, "h");
        assertInstanceOf(IdempotencyService.InFlight.class, dup);
    }
}
