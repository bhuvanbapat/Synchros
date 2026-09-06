package com.flashreserve.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import com.flashreserve.security.JwtService;

import static org.junit.jupiter.api.Assertions.*;

/**
 * API-level security tests: JWT auth required, IDOR blocked, admin
 * protection, malformed input, SQL-injection resistance, token tampering.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiSecurityIT extends PostgresIntegrationBase {

    @LocalServerPort
    int port;

    @Autowired
    JwtService jwtService;

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

    @Test
    void unauthenticatedRequestsAreRejected() {
        int resp = rest().get().uri("/api/events")
                .exchange((req, r) -> r.getStatusCode().value());
        assertEquals(401, resp);
    }

    @Test
    void wrongPasswordRejected() {
        int resp = rest().post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"email\":\"alice@example.com\",\"password\":\"wrong\"}")
                .exchange((req, r) -> r.getStatusCode().value());
        assertEquals(401, resp);
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = bearer("alice@example.com", "password");
        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        int resp = rest().get().uri("/api/events")
                .headers(h -> h.setBearerAuth(tampered.replace("Bearer ", "")))
                .exchange((req, r) -> r.getStatusCode().value());
        assertEquals(401, resp);
    }

    @Test
    void expiredTokenIsRejected() {
        // Issue directly with a negative-TTL instance: verification must
        // fail the expiry check and the API must return 401.
        var expired = new JwtService("test-secret-0123456789-test-secret-0123456789", -120);
        String token = expired.issue(1L, "alice@example.com", "USER");
        int resp = rest().get().uri("/api/events")
                .headers(h -> h.setBearerAuth(token))
                .exchange((req, r) -> r.getStatusCode().value());
        assertEquals(401, resp);
    }

    @Test
    void adminEndpointsRequireAdminRole() {
        int userResp = rest().get().uri("/api/admin/metrics")
                .headers(h -> h.setBearerAuth(bearer("alice@example.com", "password").replace("Bearer ", "")))
                .exchange((req, r) -> r.getStatusCode().value());
        assertEquals(403, userResp);

        int adminResp = rest().get().uri("/api/admin/metrics")
                .headers(h -> h.setBearerAuth(bearer("admin@flashreserve.dev", "password").replace("Bearer ", "")))
                .exchange((req, r) -> r.getStatusCode().value());
        assertEquals(200, adminResp);
    }

    @Test
    void userCannotReadAnotherUsersReservation() {
        String alice = bearer("alice@example.com", "password");
        var events = rest().get().uri("/api/events")
                .headers(h -> h.setBearerAuth(alice.replace("Bearer ", "")))
                .retrieve().toEntity(String.class);
        String body = events.getBody();
        String eventId = extractJsonStringField(body, "id");
        assertNotNull(eventId, "seeded event must exist");

        var createResp = rest().post().uri("/api/reservations")
                .headers(h -> {
                    h.setBearerAuth(alice.replace("Bearer ", ""));
                    h.setContentType(MediaType.APPLICATION_JSON);
                    h.set("Idempotency-Key", "sec-it-1");
                })
                .body("{\"eventId\":\"" + eventId + "\",\"section\":\"BALCONY\",\"quantity\":1}")
                .retrieve().toEntity(String.class);
        assertEquals(201, createResp.getStatusCode().value());
        String reservationId = extractJsonStringField(createResp.getBody(), "id");

        String bob = bearer("bob@example.com", "password");
        var bobResp = rest().get().uri("/api/reservations/" + reservationId)
                .headers(h -> h.setBearerAuth(bob.replace("Bearer ", "")))
                .exchange((req, resp) -> resp.getStatusCode().value());
        assertEquals(403, bobResp, "bob must not read alice's reservation");

        var aliceResp = rest().get().uri("/api/reservations/" + reservationId)
                .headers(h -> h.setBearerAuth(alice.replace("Bearer ", "")))
                .retrieve().toEntity(String.class);
        assertEquals(200, aliceResp.getStatusCode().value());
    }

    @Test
    void malformedBodyRejected() {
        String alice = bearer("alice@example.com", "password");
        var resp = rest().post().uri("/api/reservations")
                .headers(h -> {
                    h.setBearerAuth(alice.replace("Bearer ", ""));
                    h.setContentType(MediaType.APPLICATION_JSON);
                })
                .body("{not json")
                .exchange((req, r) -> r.getStatusCode().value());
        assertEquals(400, resp);
    }

    @Test
    void invalidQuantityRejected() {
        String alice = bearer("alice@example.com", "password");
        var events = rest().get().uri("/api/events")
                .headers(h -> h.setBearerAuth(alice.replace("Bearer ", "")))
                .retrieve().toEntity(String.class);
        String eventId = extractJsonStringField(events.getBody(), "id");

        var resp = rest().post().uri("/api/reservations")
                .headers(h -> {
                    h.setBearerAuth(alice.replace("Bearer ", ""));
                    h.setContentType(MediaType.APPLICATION_JSON);
                })
                .body("{\"eventId\":\"" + eventId + "\",\"section\":\"BALCONY\",\"quantity\":-5}")
                .exchange((req, r) -> r.getStatusCode().value());
        assertEquals(400, resp);
    }

    @Test
    void sqlInjectionAttemptsFailSafely() {
        String alice = bearer("alice@example.com", "password");
        var events = rest().get().uri("/api/events")
                .headers(h -> h.setBearerAuth(alice.replace("Bearer ", "")))
                .retrieve().toEntity(String.class);
        String eventId = extractJsonStringField(events.getBody(), "id");

        int status = rest().post().uri("/api/reservations")
                .headers(h -> {
                    h.setBearerAuth(alice.replace("Bearer ", ""));
                    h.setContentType(MediaType.APPLICATION_JSON);
                })
                .body("{\"eventId\":\"" + eventId + "\",\"section\":\"BALCONY' OR '1'='1\",\"quantity\":1}")
                .exchange((req, r) -> r.getStatusCode().value());
        assertTrue(status == 404 || status == 409,
                "injection must fail safely, got " + status);
    }

    private static String extractJsonStringField(String json, String field) {
        if (json == null) return null;
        // Whitespace-tolerant: jsonb round-trips through PG may add spaces.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
