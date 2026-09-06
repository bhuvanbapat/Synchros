package com.flashreserve.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Webhook message authentication: HMAC-SHA256 over the exact raw payload
 * bytes, base64-encoded in the X-Signature header (Stripe-style). The
 * gateway (relay) signs; the webhook controller verifies with a
 * constant-time comparison. The shared secret is env-injected
 * (PAYMENT_WEBHOOK_SECRET) and differs per environment.
 */
@Service
public class WebhookSigner {

    private final byte[] secret;

    public WebhookSigner(@Value("${flashreserve.payment.webhook-secret:}") String secret) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException(
                    "flashreserve.payment.webhook-secret must be set (>= 32 chars) — "
                            + "configure PAYMENT_WEBHOOK_SECRET");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String sign(byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(rawBody));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }
    public String sign(String body) {
        return sign(body.getBytes(StandardCharsets.UTF_8));
    }

    /** Constant-time verification; never short-circuits on first mismatch. */
    public boolean verify(byte[] rawBody, String presentedB64) {
        if (presentedB64 == null) return false;
        byte[] presented;
        try {
            presented = Base64.getDecoder().decode(presentedB64);
        } catch (IllegalArgumentException e) {
            return false;
        }
        byte[] expected;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            expected = mac.doFinal(rawBody);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
        return MessageDigest.isEqual(expected, presented);
    }
}
