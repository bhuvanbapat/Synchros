package com.Synchros.idempotency;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fingerprint determinism: identical requests hash identically regardless
 * of JSON key order is NOT guaranteed by map iteration — the service
 * serializes typed records via Jackson, whose field order is stable per
 * class. These tests pin the behavioral contract used by the API tests.
 */
class FingerprintTest {

    // A record with the same shape IdempotencyService serializes.
    record SampleRequest(java.util.UUID eventId, String section, int quantity) {
    }

    private final IdempotencyHarness harness = new IdempotencyHarness();

    static class IdempotencyHarness {
        String hashOf(Object o) {
            // mimic: SHA-256 over a canonical JSON of the record
            String canonical = tools.jackson.databind.json.JsonMapper.builder()
                    .build().writeValueAsString(o);
            try {
                MessageDigest d = MessageDigest.getInstance("SHA-256");
                return HexFormat.of().formatHex(
                        d.digest(canonical.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Test
    void sameRequestProducesSameFingerprint() {
        var a = new SampleRequest(java.util.UUID.nameUUIDFromBytes("e1".getBytes()), "FLOOR", 2);
        var b = new SampleRequest(java.util.UUID.nameUUIDFromBytes("e1".getBytes()), "FLOOR", 2);
        assertEquals(harness.hashOf(a), harness.hashOf(b));
    }

    @Test
    void differentRequestProducesDifferentFingerprint() {
        var a = new SampleRequest(java.util.UUID.nameUUIDFromBytes("e1".getBytes()), "FLOOR", 2);
        var b = new SampleRequest(java.util.UUID.nameUUIDFromBytes("e1".getBytes()), "FLOOR", 3);
        assertNotEquals(harness.hashOf(a), harness.hashOf(b));
    }
}
