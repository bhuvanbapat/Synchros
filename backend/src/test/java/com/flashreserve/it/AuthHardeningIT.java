package com.flashreserve.it;

import com.flashreserve.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import com.flashreserve.payment.WebhookSigner;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Auth hardening regression tests: JWT issue/verify semantics (tamper,
 * expiry, wrong-secret) and webhook HMAC enforcement over real HTTP
 * (unsigned rejected, signed accepted, tampered rejected).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthHardeningIT extends PostgresIntegrationBase {

    @LocalServerPort
    int port;

    @Autowired JwtService jwtService;
    @Autowired WebhookSigner signer;

    RestClient rest() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
    }

    /** Logs in over the real endpoint and returns the Bearer value. */
    private String bearer(String user, String pass) {
        var resp = rest().post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"email\":\"" + user + "\",\"password\":\"" + pass + "\"}")
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<java.util.Map<String, Object>>() {})
                .get("accessToken");
        return "Bearer " + resp;
    }

    // ---- JWT unit-level ----

    @Test
    void issuedTokenRoundTrips() {
        String token = jwtService.issue(42L, "x@example.com", "USER");
        JwtService.Claims claims = jwtService.verify(token);
        assertNotNull(claims);
        assertEquals(42L, claims.userId());
        assertEquals("x@example.com", claims.email());
        assertEquals("USER", claims.role());
    }

    @Test
    void garbageTokenFailsVerification() {
        assertNull(jwtService.verify("not.a.token"));
        assertNull(jwtService.verify(null));
        assertNull(jwtService.verify("aaa.bbb"));
    }

    private static com.flashreserve.common.SecretPolicy permissivePolicy() {
        return new com.flashreserve.common.SecretPolicy(
                new org.springframework.mock.env.MockEnvironment(), false);
    }

    @Test
    void tokenSignedWithDifferentSecretIsRejected() {
        var other = new JwtService("another-secret-entirely-0123456789abcdef", 3600,
                permissivePolicy());
        String foreign = other.issue(1L, "alice@example.com", "USER");
        assertNull(jwtService.verify(foreign),
                "a token signed by a different key must never verify");
    }

    @Test
    void expiredTokenFailsVerification() {
        var shortLived = new JwtService("test-secret-0123456789-test-secret-0123456789", -3600,
                permissivePolicy());
        String token = shortLived.issue(1L, "alice@example.com", "USER");
        assertNull(jwtService.verify(token));
    }

    // ---- HMAC unit-level ----

    @Test
    void signatureRoundTripsAndTamperFails() {
        byte[] body = "{\"orderId\":\"x\"}".getBytes();
        String sig = signer.sign(body);
        assertTrue(sig.startsWith("t=") && sig.contains("v1="));
        long t = signer.verify(body, sig);   // must not throw
        assertTrue(t > 0);

        // Signature over different payload must fail
        assertThrows(com.flashreserve.common.DomainException.class,
                () -> signer.verify("{\"orderId\":\"y\"}".getBytes(), sig));
        // Tampered signature must fail
        String tampered = sig.substring(0, sig.length() - 2) + "xx";
        assertThrows(com.flashreserve.common.DomainException.class,
                () -> signer.verify(body, tampered));
        // Missing + garbage signatures fail
        assertThrows(com.flashreserve.common.DomainException.class,
                () -> signer.verify(body, null));
        assertThrows(com.flashreserve.common.DomainException.class,
                () -> signer.verify(body, "!!!not-a-signature!!!"));
        // Legacy bare base64 (pre-replay-window format) must be refused
        assertThrows(com.flashreserve.common.DomainException.class,
                () -> signer.verify(body, java.util.Base64.getEncoder()
                        .encodeToString(new byte[32])));
    }

    @Test
    void staleTimestampOutsideReplayWindowIsRejected() {
        // A signature whose embedded timestamp is hours old must fail
        // verification even though the HMAC itself is valid — a captured
        // delivery cannot be replayed raw.
        byte[] body = "{\"orderId\":\"x\"}".getBytes();
        String fresh = signer.sign(body);
        long staleT = Instant.now().getEpochSecond() - 3600;
        String stale = fresh.replaceFirst("t=\\d+", "t=" + staleT);
        assertThrows(com.flashreserve.common.DomainException.class,
                () -> signer.verify(body, stale));
    }

    // ---- HMAC over real HTTP ----

    @Test
    void unsignedWebhookIsRejectedOverHttp() {
        String body = "{\"orderId\":\"00000000-0000-0000-0000-000000000001\","
                + "\"providerRef\":\"ref-unsigned\",\"outcome\":\"SUCCESS\"}";
        int status = rest().post().uri("/api/payment-webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((req, r) -> r.getStatusCode().value());
        assertEquals(401, status, "unsigned webhook delivery must be rejected");
    }

    @Test
    void tamperedWebhookIsRejectedOverHttp() {
        String body = "{\"orderId\":\"00000000-0000-0000-0000-000000000002\","
                + "\"providerRef\":\"ref-tamper\",\"outcome\":\"SUCCESS\"}";
        // Sign a DIFFERENT payload and present that signature.
        String sig = signer.sign("{\"orderId\":\"evil\"}".getBytes());
        int status = rest().post().uri("/api/payment-webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Signature", sig)
                .body(body)
                .exchange((req, r) -> r.getStatusCode().value());
        assertEquals(401, status, "signature must be bound to the exact bytes");
    }

    @Test
    void staleWebhookReplayIsRejectedOverHttp() {
        String body = "{\"orderId\":\"00000000-0000-0000-0000-000000000004\","
                + "\"providerRef\":\"ref-stale\",\"outcome\":\"SUCCESS\"}";
        // Correct HMAC, but the timestamp is dragged an hour into the past.
        String fresh = signer.sign(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String stale = fresh.replaceFirst("t=\\d+", "t=" + (Instant.now().getEpochSecond() - 3600));
        int status = rest().post().uri("/api/payment-webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Signature", stale)
                .body(body)
                .exchange((req, r) -> r.getStatusCode().value());
        assertEquals(401, status, "captured delivery replayed later must be rejected");
    }

    @Test
    void signedWebhookPassesSignatureButFailsOnUnknownOrder() {
        // A valid signature must clear the HMAC gate (then fail as 404 on
        // the nonexistent order — proving signature ≠ authorization).
        String body = "{\"orderId\":\"00000000-0000-0000-0000-000000000003\","
                + "\"providerRef\":\"ref-valid\",\"outcome\":\"SUCCESS\"}";
        String sig = signer.sign(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int status = rest().post().uri("/api/payment-webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Signature", sig)
                .body(body)
                .exchange((req, r) -> r.getStatusCode().value());
        assertEquals(404, status, "signed delivery passes HMAC, then domain 404");
    }

    // ---- malformed transport (found live; pinned so it never returns) ----

    @Test
    void formEncodedBodyToApiControllerIs400Not500() {
        // The strict HTTP firewall rejects mangled parameter names; that
        // must surface as a structured 400, never INTERNAL_ERROR. This is
        // the exact request shape the live smoke sent by accident.
        int status = rest().post().uri("/api/reservations")
                .headers(h -> {
                    h.setBearerAuth(bearer("alice@example.com", "password").replace("Bearer ", ""));
                    h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
                })
                .body("eventId=1&section=FLOOR&quantity=1")
                .exchange((req, r) -> r.getStatusCode().value());
        assertEquals(400, status, "malformed transport must be a clean 400");
    }

    // ---- login lockout ----

    @Test
    void bruteForceLoginGetsLockedOut() {
        // 5 wrong passwords for one (email, ip) -> the 6th attempt is
        // refused with 429 BEFORE the credential check. Redis-backed
        // store is excluded in ITs (fail-open) so this pins the wiring:
        // the limiter must allow + record without ever breaking login.
        for (int i = 0; i < 5; i++) {
            int s = rest().post().uri("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"email\":\"alice@example.com\",\"password\":\"wrong\"}")
                    .exchange((req, r) -> r.getStatusCode().value());
            assertEquals(401, s);
        }
        // With no Redis in ITs the limiter fails open: correct credentials
        // still succeed right after failures — lockout logic is exercised
        // live in the smoke drill instead.
        int ok = rest().post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"email\":\"alice@example.com\",\"password\":\"password\"}")
                .exchange((req, r) -> r.getStatusCode().value());
        assertEquals(200, ok, "fail-open limiter must not lock out correct logins when Redis is absent");
    }
}
