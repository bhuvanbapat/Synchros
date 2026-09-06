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

import static org.junit.jupiter.api.Assertions.*;

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

    @Test
    void tokenSignedWithDifferentSecretIsRejected() {
        var other = new JwtService("another-secret-entirely-0123456789abcdef", 3600);
        String foreign = other.issue(1L, "alice@example.com", "USER");
        assertNull(jwtService.verify(foreign),
                "a token signed by a different key must never verify");
    }

    @Test
    void expiredTokenFailsVerification() {
        var shortLived = new JwtService("test-secret-0123456789-test-secret-0123456789", -3600);
        String token = shortLived.issue(1L, "alice@example.com", "USER");
        assertNull(jwtService.verify(token));
    }

    // ---- HMAC unit-level ----

    @Test
    void signatureRoundTripsAndTamperFails() {
        byte[] body = "{\"orderId\":\"x\"}".getBytes();
        String sig = signer.sign(body);
        assertTrue(signer.verify(body, sig));
        assertFalse(signer.verify("{\"orderId\":\"y\"}".getBytes(), sig),
                "signature over different payload must fail");
        assertFalse(signer.verify(body, sig.substring(0, sig.length() - 2) + "xx"),
                "tampered signature must fail");
        assertFalse(signer.verify(body, null));
        assertFalse(signer.verify(body, "!!!not-base64!!!"));
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
        String sig = signer.sign("{\"orderId\":\"evil\"}");
        int status = rest().post().uri("/api/payment-webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Signature", sig)
                .body(body)
                .exchange((req, r) -> r.getStatusCode().value());
        assertEquals(401, status, "signature must be bound to the exact bytes");
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
}
