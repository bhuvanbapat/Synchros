package com.Synchros.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/**
 * Stateless JWT issuer/verifier using only the JDK (HMAC-SHA256 signing —
 * no external library; the token format is standard JWS compact
 * serialization).
 *
 * Design choices:
 *  - HS256 (symmetric) because this is a single-service demo: the signer
 *    and verifier are the same process, so a shared secret via env is the
 *    simplest correct option. Multi-service deployments would switch to
 *    RS256 (public-key verification) without touching callers.
 *  - Subject = email; a custom claim carries the numeric DB user id and
 *    role so ownership checks (userId scoping) work exactly as with Basic
 *    auth — the security model is auth-mechanism-agnostic.
 *  - Expiry is enforced at verification; a small clock leeway absorbs
 *    restart skew. Tokens are signed, never encrypted: no secrets in
 *    claims.
 */
@Service
public class JwtService {

    private static final java.util.Base64.Encoder NO_PAD = Base64.getUrlEncoder().withoutPadding();

    private final byte[] secret;
    private final long ttlSeconds;

    public JwtService(@Value("${Synchros.jwt.secret:}") String secret,
                      @Value("${Synchros.jwt.ttl-seconds:3600}") long ttlSeconds,
                      com.Synchros.common.SecretPolicy secretPolicy) {
        secretPolicy.check("Synchros.jwt.secret (JWT_SECRET)", secret);
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds;
    }

    public record Claims(Long userId, String email, String role, Instant expiresAt) {
    }

    /** Issues a signed token for the given principal. */
    public String issue(Long userId, String email, String role) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(ttlSeconds);
        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payload = "{\"sub\":\"" + email + "\",\"uid\":" + userId
                + ",\"role\":\"" + role + "\",\"iat\":" + now.getEpochSecond()
                + ",\"exp\":" + exp.getEpochSecond() + "}";
        String signingInput = NO_PAD.encodeToString(header.getBytes(StandardCharsets.UTF_8))
                + "." + NO_PAD.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + NO_PAD.encodeToString(sign(signingInput));
    }

    /**
     * Verifies signature + expiry. Returns claims, or null when the token
     * is invalid/expired/malformed — callers translate null to 401.
     */
    public Claims verify(String token) {
        if (token == null) return null;
        String[] parts = token.split("\\.");
        if (parts.length != 3) return null;

        String signingInput = parts[0] + "." + parts[1];
        byte[] expected = sign(signingInput);
        byte[] presented;
        try {
            presented = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            return null;
        }
        // Constant-time comparison — signature checks must not leak timing.
        if (!MessageDigest.isEqual(expected, presented)) return null;

        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        Long uid = extractNumber(payload, "\"uid\":");
        String email = extractString(payload, "\"sub\":\"");
        String role = extractString(payload, "\"role\":\"");
        Long exp = extractNumber(payload, "\"exp\":");
        if (uid == null || email == null || role == null || exp == null) return null;

        // 60s leeway for clock skew between issue and verify.
        if (Instant.now().getEpochSecond() > exp + 60) return null;

        return new Claims(uid, email, role, Instant.ofEpochSecond(exp));
    }

    private byte[] sign(String input) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    private static Long extractNumber(String json, String key) {
        int i = json.indexOf(key);
        if (i < 0) return null;
        int start = i + key.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)))) end++;
        if (end == start) return null;
        try {
            return Long.parseLong(json.substring(start, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String extractString(String json, String key) {
        int i = json.indexOf(key);
        if (i < 0) return null;
        int start = i + key.length();
        int end = json.indexOf('"', start);
        if (end < 0) return null;
        return json.substring(start, end);
    }
}
