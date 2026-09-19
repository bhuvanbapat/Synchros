package com.synchros.it;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end idempotency over HTTP (JWT auth): same key + same body => one
 * logical reservation; different body + same key => 409 conflict.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiIdempotencyIT extends PostgresIntegrationBase {

    @LocalServerPort
    int port;

    RestClient rest() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
    }

    private String token(String user) {
        var resp = rest().post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"email\":\"" + user + "\",\"password\":\"password\"}")
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<java.util.Map<String, Object>>() {});
        return (String) resp.get("accessToken");
    }

    @Test
    void repeatedSameKeySameBodyCreatesOneReservation() {
        String alice = token("alice@example.com");
        var events = rest().get().uri("/api/events")
                .headers(h -> h.setBearerAuth(alice))
                .retrieve().toEntity(String.class);
        String eventId = extract(events.getBody(), "id");

        String body = "{\"eventId\":\"" + eventId + "\",\"section\":\"BALCONY\",\"quantity\":1}";

        var first = rest().post().uri("/api/reservations")
                .headers(h -> {
                    h.setBearerAuth(alice);
                    h.setContentType(MediaType.APPLICATION_JSON);
                    h.set("Idempotency-Key", "e2e-idem-1");
                })
                .body(body)
                .retrieve().toEntity(String.class);
        assertEquals(201, first.getStatusCode().value());
        String id1 = extract(first.getBody(), "id");

        var second = rest().post().uri("/api/reservations")
                .headers(h -> {
                    h.setBearerAuth(alice);
                    h.setContentType(MediaType.APPLICATION_JSON);
                    h.set("Idempotency-Key", "e2e-idem-1");
                })
                .body(body)
                .exchange((req, resp) -> {
                    assertEquals("true", resp.getHeaders().getFirst("X-Idempotent-Replay"));
                    return new String(resp.getBody().readAllBytes());
                });
        assertEquals(id1, extract(second, "id"),
                "replay must return the original reservation");

        var third = rest().post().uri("/api/reservations")
                .headers(h -> {
                    h.setBearerAuth(alice);
                    h.setContentType(MediaType.APPLICATION_JSON);
                    h.set("Idempotency-Key", "e2e-idem-1");
                })
                .body(body)
                .exchange((req, resp) -> new String(resp.getBody().readAllBytes()));
        assertEquals(id1, extract(third, "id"));
    }

    @Test
    void sameKeyDifferentBodyConflicts() {
        String bob = token("bob@example.com");
        var events = rest().get().uri("/api/events")
                .headers(h -> h.setBearerAuth(bob))
                .retrieve().toEntity(String.class);
        String eventId = extract(events.getBody(), "id");

        int first = rest().post().uri("/api/reservations")
                .headers(h -> {
                    h.setBearerAuth(bob);
                    h.setContentType(MediaType.APPLICATION_JSON);
                    h.set("Idempotency-Key", "e2e-idem-2");
                })
                .body("{\"eventId\":\"" + eventId + "\",\"section\":\"BALCONY\",\"quantity\":1}")
                .exchange((req, resp) -> resp.getStatusCode().value());
        assertEquals(201, first);

        int second = rest().post().uri("/api/reservations")
                .headers(h -> {
                    h.setBearerAuth(bob);
                    h.setContentType(MediaType.APPLICATION_JSON);
                    h.set("Idempotency-Key", "e2e-idem-2");
                })
                .body("{\"eventId\":\"" + eventId + "\",\"section\":\"BALCONY\",\"quantity\":2}")
                .exchange((req, resp) -> resp.getStatusCode().value());
        assertEquals(409, second);
    }

    private static String extract(String json, String field) {
        if (json == null) return null;
        // Whitespace-tolerant: jsonb round-trips through PG may add spaces.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
