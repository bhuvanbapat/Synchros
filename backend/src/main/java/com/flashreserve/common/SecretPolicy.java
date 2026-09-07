package com.flashreserve.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Fail-closed secret policy: secrets must be >= 32 chars, AND the known
 * development placeholder values are refused whenever a "prod-like"
 * profile is active. A deployment that forgets to override JWT_SECRET /
 * PAYMENT_WEBHOOK_SECRET must crash at boot — never silently run with a
 * secret that is printed in the repository.
 */
@Component
public class SecretPolicy {

    /** The exact placeholder values shipped in .env.example / compose. */
    static final Set<String> DEV_PLACEHOLDERS = Set.of(
            "dev-only-jwt-secret-change-me-0123456789abcdef",
            "dev-only-webhook-secret-change-me-0123456789");

    private final boolean prodLike;

    public SecretPolicy(Environment env,
                        @Value("${flashreserve.secret-policy.enforce-non-dev:true}")
                        boolean enforceNonDev) {
        // Any profile other than the local-dev defaults counts as prod-like;
        // explicit opt-out exists for machines that genuinely want the demo.
        this.prodLike = enforceNonDev && java.util.Arrays.stream(env.getActiveProfiles())
                .anyMatch(p -> !p.isBlank() && !p.equals("local") && !p.equals("dev")
                        && !p.equals("test"));
    }

    public void check(String name, String secret) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException(name
                    + " must be set to at least 32 characters — booting without it is refused");
        }
        if (prodLike && DEV_PLACEHOLDERS.contains(secret)) {
            throw new IllegalStateException(name
                    + " is still the repository development placeholder — set a real "
                    + "secret (this check fails closed under prod-like profiles)");
        }
    }
}
