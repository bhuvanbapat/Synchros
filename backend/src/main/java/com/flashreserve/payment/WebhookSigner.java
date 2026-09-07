package com.flashreserve.payment;

import com.flashreserve.common.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/**
 * Webhook message authentication — Stripe-style versioned signatures with
 * a replay window. The X-Signature header carries
 *
 *     t=<unix-seconds>,v1=<base64-HMAC-SHA256(secret, t + "." + payload)>
 *
 * so every delivery is bound to its issue time. Verification recomputes the
 * HMAC over the presented timestamp + the exact raw request bytes and
 * rejects deliveries older than the tolerance (default 5 minutes) — a
 * captured valid request can no longer be replayed raw hours later. The
 * timestamp is part of the signed material, so it cannot be forged or
 * refreshed without the secret. Legacy bare-signature deliveries are
 * rejected (v1 is mandatory).
 *
 * The shared secret is env-injected (PAYMENT_WEBHOOK_SECRET), length- and
 * placeholder-checked by SecretPolicy, never logged.
 */
@Service
public class WebhookSigner {

    private final byte[] secret;
    private final long toleranceSeconds;

    public WebhookSigner(@Value("${flashreserve.payment.webhook-secret:}") String secret,
                          @Value("${flashreserve.payment.webhook-replay-tolerance-seconds:300}")
                          long toleranceSeconds,
                          com.flashreserve.common.SecretPolicy secretPolicy) {
        secretPolicy.check(
                "flashreserve.payment.webhook-secret (PAYMENT_WEBHOOK_SECRET)", secret);
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.toleranceSeconds = toleranceSeconds;
    }

    /** Signed-material layout: "<t>.<payload bytes>". */
    private static final byte DOT = '.';

    /** Signs a delivery as it goes out: returns the full header value. */
    public String sign(byte[] rawBody) {
        long t = Instant.now().getEpochSecond();
        byte[] signed = signedMaterial(t, rawBody);
        byte[] mac = hmac(signed);
        return "t=" + t + ",v1=" + Base64.getEncoder().encodeToString(mac);
    }

    public String sign(String body) {
        return sign(body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Verifies a presented "t=...,v1=..." header against the exact raw
     * request bytes. Constant-time compare; age outside the tolerance
     * window fails. Returns the verified timestamp, or throws on any
     * rejection — callers convert to 401.
     */
    public long verify(byte[] rawBody, String presented) {
        if (presented == null) return fail();
        long t;
        byte[] presentedMac;
        try {
            int v1 = presented.indexOf("v1=");
            if (!presented.startsWith("t=") || v1 < 0) return fail();
            t = Long.parseLong(presented.substring(2, v1 - 1));
            presentedMac = Base64.getDecoder().decode(presented.substring(v1 + 3));
        } catch (IllegalArgumentException e) { // NumberFormatException ⊂ IAE
            return fail();
        }
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - t) > toleranceSeconds) return fail();

        byte[] expected = hmac(signedMaterial(t, rawBody));
        if (!MessageDigest.isEqual(expected, presentedMac)) return fail();
        return t;
    }

    private static long fail() {
        throw new DomainException(DomainException.ErrorCode.WEBHOOK_SIGNATURE_INVALID,
                "Webhook signature verification failed");
    }

    private static byte[] signedMaterial(long t, byte[] body) {
        byte[] ts = String.valueOf(t).getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[ts.length + 1 + body.length];
        System.arraycopy(ts, 0, out, 0, ts.length);
        out[ts.length] = DOT;
        System.arraycopy(body, 0, out, ts.length + 1, body.length);
        return out;
    }

    private byte[] hmac(byte[] material) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(material);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }
}
