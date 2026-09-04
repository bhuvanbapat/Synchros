package com.flashreserve.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;

/**
 * API-level security tests: auth required, IDOR blocked, admin protection,
 * malformed input, SQL-injection resistance.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiSecurityIT extends PostgresIntegrationBase {

    @LocalServerPort
    int port;

    RestClient rest() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
    }

    private HttpHeaders basic(String user, String pass) {
        HttpHeaders h = new HttpHeaders();
        h.setBasicAuth(user, pass);
        return h;
    }

    @Test
    void unauthenticatedRequestsAreRejected() {
        int resp = rest().get().uri("/api/events")
                .exchange((req, r) -> r.getStatusCode().value());
        assertEquals(401, resp);
    }

    @Test
    void wrongPasswordRejected() {
        int resp = rest().get().uri("/api/events")
                .headers(h -> h.setBasicAuth("alice@example.com", "wrong"))
                .exchange((req, r) -> r.getStatusCode().value());
        assertEquals(401, resp);
    }

    @Test
    void adminEndpointsRequireAdminRole() {
        int userResp = rest().get().uri("/api/admin/metrics")
                .headers(h -> h.setBasicAuth("alice@example.com", "password"))
                .exchange((req, r) -> r.getStatusCode().value());
        assertEquals(403, userResp);

        int adminResp = rest().get().uri("/api/admin/metrics")
                .headers(h -> h.setBasicAuth("admin@flashreserve.dev", "password"))
                .exchange((req, r) -> r.getStatusCode().value());
        assertEquals(200, adminResp);
    }

    @Test
    void userCannotReadAnotherUsersReservation() {
        var events = rest().get().uri("/api/events")
                .headers(h -> h.setBasicAuth("alice@example.com", "password"))
                .retrieve().toEntity(String.class);
        String body = events.getBody();
        String eventId = extractJsonStringField(body, "id");
        assertNotNull(eventId, "seeded event must exist");

        var createResp = rest().post().uri("/api/reservations")
                .headers(h -> {
                    h.setBasicAuth("alice@example.com", "password");
                    h.setContentType(MediaType.APPLICATION_JSON);
                    h.set("Idempotency-Key", "sec-it-1");
                })
                .body("{\"eventId\":\"" + eventId + "\",\"section\":\"BALCONY\",\"quantity\":1}")
                .retrieve().toEntity(String.class);
        assertEquals(201, createResp.getStatusCode().value());
        String reservationId = extractJsonStringField(createResp.getBody(), "id");

        var bobResp = rest().get().uri("/api/reservations/" + reservationId)
                .headers(h -> h.setBasicAuth("bob@example.com", "password"))
                .exchange((req, resp) -> resp.getStatusCode().value());
        assertEquals(403, bobResp, "bob must not read alice's reservation");

        var aliceResp = rest().get().uri("/api/reservations/" + reservationId)
                .headers(h -> h.setBasicAuth("alice@example.com", "password"))
                .retrieve().toEntity(String.class);
        assertEquals(200, aliceResp.getStatusCode().value());
    }

    @Test
    void malformedBodyRejected() {
        var resp = rest().post().uri("/api/reservations")
                .headers(h -> {
                    h.setBasicAuth("alice@example.com", "password");
                    h.setContentType(MediaType.APPLICATION_JSON);
                })
                .body("{not json")
                .exchange((req, r) -> r.getStatusCode().value());
        assertEquals(400, resp);
    }

    @Test
    void invalidQuantityRejected() {
        var events = rest().get().uri("/api/events")
                .headers(h -> h.setBasicAuth("alice@example.com", "password"))
                .retrieve().toEntity(String.class);
        String eventId = extractJsonStringField(events.getBody(), "id");

        var resp = rest().post().uri("/api/reservations")
                .headers(h -> {
                    h.setBasicAuth("alice@example.com", "password");
                    h.setContentType(MediaType.APPLICATION_JSON);
                })
                .body("{\"eventId\":\"" + eventId + "\",\"section\":\"BALCONY\",\"quantity\":-5}")
                .exchange((req, r) -> r.getStatusCode().value());
        assertEquals(400, resp);
    }

    @Test
    void sqlInjectionAttemptsFailSafely() {
        var events = rest().get().uri("/api/events")
                .headers(h -> h.setBasicAuth("alice@example.com", "password"))
                .retrieve().toEntity(String.class);
        String eventId = extractJsonStringField(events.getBody(), "id");

        int status = rest().post().uri("/api/reservations")
                .headers(h -> {
                    h.setBasicAuth("alice@example.com", "password");
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
