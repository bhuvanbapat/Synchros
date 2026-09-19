package com.Synchros.payment;

import com.Synchros.audit.AuditService;
import com.Synchros.common.DomainException;
import com.Synchros.order.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Webhook processing. Idempotent per (order, providerRef): duplicates are
 * recorded as DUPLICATE_CALLBACK attempts with no business effect.
 * Malformed callbacks are rejected with INVALID_REQUEST before any state
 * change. Signature verification happens at the HTTP boundary
 * (PaymentWebhookController); the relay's in-process deliveries are
 * pre-trusted because the relay itself signs them.
 */
@Service
public class PaymentWebhookService {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookService.class);

    private final OrderService orderService;
    private final AuditService auditService;
    private final WebhookSigner signer;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    public PaymentWebhookService(OrderService orderService, AuditService auditService,
                                 WebhookSigner signer,
                                 tools.jackson.databind.ObjectMapper objectMapper) {
        this.orderService = orderService;
        this.auditService = auditService;
        this.signer = signer;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void handleCallback(UUID orderId, String providerRef, MockPaymentGateway.Outcome outcome) {
        validate(orderId, providerRef, outcome);

        var order = orderService.applyPaymentResult(orderId, null, providerRef, outcome);

        auditService.record("payment-webhook", "PAYMENT_CALLBACK_RECEIVED", "order",
                orderId.toString(),
                Map.of("providerRef", providerRef, "outcome", outcome.name(),
                        "resultingState", order.getState().name()));
        log.info("payment webhook applied order={} outcome={} resultingState={}",
                orderId, outcome, order.getState());
    }

    /**
     * Signed delivery path used by the in-process relay: verifies the
     * HMAC (including the replay window) over the exact payload bytes,
     * then applies. This exercises the same verification the HTTP
     * endpoint performs on every simulated payment, so a misconfigured
     * secret fails fast in tests, not prod.
     */
    public void handleSignedCallback(String json, String signature) {
        signer.verify(json.getBytes(java.nio.charset.StandardCharsets.UTF_8), signature);
        try {
            var node = objectMapper.readTree(json);
            handleCallback(
                    UUID.fromString(node.get("orderId").asText()),
                    node.get("providerRef").asText(),
                    MockPaymentGateway.Outcome.valueOf(node.get("outcome").asText()));
        } catch (DomainException e) {
            throw e;
        } catch (Exception e) {
            throw new DomainException(DomainException.ErrorCode.INVALID_REQUEST,
                    "Malformed payment callback");
        }
    }

    private static void validate(UUID orderId, String providerRef,
                                 MockPaymentGateway.Outcome outcome) {
        if (orderId == null || providerRef == null || providerRef.isBlank()
                || providerRef.length() > 128 || outcome == null) {
            throw new DomainException(DomainException.ErrorCode.INVALID_REQUEST,
                    "Malformed payment callback");
        }
    }
}
