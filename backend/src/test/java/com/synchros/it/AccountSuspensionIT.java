package com.synchros.it;

import com.synchros.user.AccountAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Account-state admin regression. The account_state control existed since
 * V1 (CHECK constraint + per-request principal reload) but nothing could
 * ever SET it — control without feature. This pins the completed loop:
 *
 *   1. Admin suspends bob via the new endpoint
 *   2. bob's LIVE, previously-issued token 401s on the very next request
 *      (per-request principal reload — no revocation list needed)
 *   3. Admin reactivates bob — the SAME token works again
 *   4. Self-suspension refused; unknown user 404; garbage state 400.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountSuspensionIT extends PostgresIntegrationBase {

    @LocalServerPort
    int port;

    @Autowired AccountAdminService accountAdminService;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    RestClient rest() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
    }

    private String bearer(String email, String password) {
        var resp = rest().post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", password))
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<
                        java.util.Map<String, Object>>() {})
                .get("accessToken");
        return String.valueOf(resp);
    }

    private String publicId(String email) {
        return jdbc.queryForObject(
                "SELECT public_id::text FROM fr_user WHERE email = ?", String.class, email);
    }

    @Test
    void suspensionBindsImmediatelyAndActivationRestores() {
        String bobToken = bearer("bob@example.com", "password");
        String adminToken = bearer("admin@synchros.dev", "password");
        String bobId = publicId("bob@example.com");

        // bob can call the API before suspension.
        int before = rest().get().uri("/api/reservations")
                .headers(h -> h.setBearerAuth(bobToken))
                .exchange((req, r) -> r.getStatusCode().value());
        assertEquals(200, before);

        // Admin suspends bob.
        int susp = rest().post().uri("/api/admin/users/" + bobId + "/state")
                .headers(h -> h.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("accountState", "SUSPENDED"))
                .exchange((req, r) -> r.getStatusCode().value());
        assertEquals(200, susp);

        // bob's SAME token now 401s — per-request principal reload.
        int locked = rest().get().uri("/api/reservations")
                .headers(h -> h.setBearerAuth(bobToken))
                .exchange((req, r) -> r.getStatusCode().value());
        assertEquals(401, locked, "suspension must bind on the next request, token or no token");

        // Reactivate — the SAME token works again.
        int react = rest().post().uri("/api/admin/users/" + bobId + "/state")
                .headers(h -> h.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("accountState", "ACTIVE"))
                .exchange((req, r) -> r.getStatusCode().value());
        assertEquals(200, react);

        int after = rest().get().uri("/api/reservations")
                .headers(h -> h.setBearerAuth(bobToken))
                .exchange((req, r) -> r.getStatusCode().value());
        assertEquals(200, after, "reactivation must restore access immediately");
    }

    @Test
    void nonAdminCannotUseTheEndpoint() {
        String bobToken = bearer("bob@example.com", "password");
        int status = rest().post().uri("/api/admin/users/"
                        + publicId("bob@example.com") + "/state")
                .headers(h -> h.setBearerAuth(bobToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("accountState", "SUSPENDED"))
                .exchange((req, r) -> r.getStatusCode().value());
        assertEquals(403, status);
    }

    @Test
    void selfSuspensionIsRefused() {
        String adminToken = bearer("admin@synchros.dev", "password");
        int status = rest().post().uri("/api/admin/users/"
                        + publicId("admin@synchros.dev") + "/state")
                .headers(h -> h.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("accountState", "SUSPENDED"))
                .exchange((req, r) -> r.getStatusCode().value());
        assertEquals(400, status, "admins must not lock themselves out");
    }

    @Test
    void garbageStateValueIsRejected() {
        String adminToken = bearer("admin@synchros.dev", "password");
        int status = rest().post().uri("/api/admin/users/"
                        + publicId("bob@example.com") + "/state")
                .headers(h -> h.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("accountState", "HACKED"))
                .exchange((req, r) -> r.getStatusCode().value());
        assertEquals(400, status);
    }

    @Test
    void unknownUserIsNotFound() {
        String adminToken = bearer("admin@synchros.dev", "password");
        int status = rest().post().uri("/api/admin/users/" + UUID.randomUUID() + "/state")
                .headers(h -> h.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("accountState", "SUSPENDED"))
                .exchange((req, r) -> r.getStatusCode().value());
        assertEquals(404, status);
    }
}
