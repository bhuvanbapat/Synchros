package com.synchros.security;

import com.synchros.common.SecretPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fail-closed secret policy: length floor always enforced; repository
 * placeholder values refused under prod-like profiles (the exact
 * misconfiguration that would otherwise boot with a printed secret).
 */
class SecretPolicyTest {

    private static SecretPolicy withProfiles(boolean enforce, String... profiles) {
        var env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return new SecretPolicy(env, enforce);
    }

    @Test
    void shortSecretAlwaysRejected() {
        assertThrows(IllegalStateException.class,
                () -> withProfiles(false).check("x", "short"));
        assertThrows(IllegalStateException.class,
                () -> withProfiles(true, "prod").check("x", null));
    }

    @Test
    void devPlaceholderAllowedInLocalDev() {
        assertDoesNotThrow(() -> withProfiles(false)
                .check("jwt", "dev-only-jwt-secret-change-me-0123456789abcdef"));
        // no active profile at all (bare local run) — also allowed
        assertDoesNotThrow(() -> withProfiles(false)
                .check("jwt", "dev-only-jwt-secret-change-me-0123456789abcdef"));
    }

    @Test
    void devPlaceholderRefusedUnderProdLikeProfile() {
        assertThrows(IllegalStateException.class, () -> withProfiles(true, "prod")
                .check("jwt", "dev-only-jwt-secret-change-me-0123456789abcdef"));
        assertThrows(IllegalStateException.class, () -> withProfiles(true, "prod")
                .check("webhook", "dev-only-webhook-secret-change-me-0123456789"));
    }

    @Test
    void strongSecretPassesEverywhere() {
        assertDoesNotThrow(() -> withProfiles(true, "prod")
                .check("jwt", "a-real-64-char-secret-a-real-64-char-secret-a!!"));
    }

    @Test
    void optOutDisablesPlaceholderCheckOnly() {
        // enforce-non-dev=false keeps the length floor, drops the
        // placeholder refusal — documented escape hatch for demo machines.
        assertDoesNotThrow(() -> withProfiles(false, "prod")
                .check("jwt", "dev-only-jwt-secret-change-me-0123456789abcdef"));
        assertThrows(IllegalStateException.class,
                () -> withProfiles(false, "prod").check("jwt", "short"));
    }
}
