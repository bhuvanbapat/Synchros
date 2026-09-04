package com.flashreserve.payment;

import com.flashreserve.config.FlashReserveProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Deterministic-weighted random mock gateway. No real money, no external
 * calls; outcomes are sampled from configurable weights so load tests and
 * failure scenarios reproduce reliably. The response IS the callback —
 * the client (or test) relays it to the webhook, so webhook idempotency is
 * exercised on the real code path.
 */
@Service
public class MockPaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentGateway.class);

    public enum Outcome { SUCCESS, FAILURE, TIMEOUT }

    public record GatewayResponse(String providerRef, Outcome outcome, String message) {
    }

    private final SecureRandom random = new SecureRandom();
    private final FlashReserveProperties props;

    public MockPaymentGateway(FlashReserveProperties props) {
        this.props = props;
    }

    public GatewayResponse charge(int amountCents, UUID orderPublicId) {
        double p = random.nextDouble();
        double success = props.getPayment().getSuccessWeight();
        double failure = props.getPayment().getFailureWeight();

        Outcome outcome;
        if (p < success) {
            outcome = Outcome.SUCCESS;
        } else if (p < success + failure) {
            outcome = Outcome.FAILURE;
        } else {
            outcome = Outcome.TIMEOUT;
        }

        String ref = "mock-" + UUID.randomUUID();
        String msg = switch (outcome) {
            case SUCCESS -> "Payment captured";
            case FAILURE -> "Card declined by issuer";
            case TIMEOUT -> "Gateway did not respond in time";
        };
        log.info("mock gateway charge order={} amount={} outcome={} ref={}",
                orderPublicId, amountCents, outcome, ref);
        return new GatewayResponse(ref, outcome, msg);
    }
}
