package com.flashreserve.payment;

import com.flashreserve.audit.AuditService;
import com.flashreserve.common.DomainException;
import com.flashreserve.order.OrderService;
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
 * change.
 */
@Service
public class PaymentWebhookService {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookService.class);

    private final OrderService orderService;
    private final AuditService auditService;

    public PaymentWebhookService(OrderService orderService, AuditService auditService) {
        this.orderService = orderService;
        this.auditService = auditService;
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

    private static void validate(UUID orderId, String providerRef,
                                MockPaymentGateway.Outcome outcome) {
        if (orderId == null || providerRef == null || providerRef.isBlank()
                || providerRef.length() > 128 || outcome == null) {
            throw new DomainException(DomainException.ErrorCode.INVALID_REQUEST,
                    "Malformed payment callback");
        }
    }
}
