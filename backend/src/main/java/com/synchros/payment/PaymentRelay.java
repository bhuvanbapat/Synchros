package com.Synchros.payment;

import com.Synchros.order.Order;
import com.Synchros.order.OrderService;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Bridges the mock gateway and the real webhook path: charges via the
 * gateway, then delivers the outcome to /api/payment-webhooks exactly the
 * way an external PSP would — including HMAC-signing the payload with the
 * shared secret so the verification filter runs on every simulated payment.
 * Delivery failures are simulated by simply not calling the webhook
 * (documented in docs/EVENTS.md).
 *
 * Using an internal HTTP call would be more "realistic" but adds a
 * self-referential network dependency; calling the webhook service
 * directly keeps the demo deterministic while exercising the same code
 * path (signature check, idempotency, locks, state machines).
 */
@Component
public class PaymentRelay {

    private static final Logger log = LoggerFactory.getLogger(PaymentRelay.class);

    private final MockPaymentGateway gateway;
    private final PaymentWebhookService webhookService;
    private final OrderService orderService;
    private final WebhookSigner signer;
    private final ObjectMapper objectMapper;

    public PaymentRelay(MockPaymentGateway gateway,
                       PaymentWebhookService webhookService,
                       OrderService orderService,
                       WebhookSigner signer,
                       ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.webhookService = webhookService;
        this.orderService = orderService;
        this.signer = signer;
        this.objectMapper = objectMapper;
    }

    public PaymentSimulationDtos.ChargeResponse chargeAndDeliver(Order order, Long userId) {
        var response = gateway.charge(order.getAmountCents(), order.getPublicId());

        // Deliver via the SIGNED webhook path (system actor) — the payload
        // is signed exactly as an external PSP would, and the service
        // verifies before applying. Duplicate delivery is intentionally
        // possible here — tests replay this to prove idempotency.
        try {
            String json = objectMapper.writeValueAsString(new PaymentWebhookController.WebhookRequest(
                    order.getPublicId(), response.providerRef(), response.outcome().name()));
            String signature = signer.sign(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            webhookService.handleSignedCallback(json, signature);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deliver signed webhook", e);
        }

        Order updated = orderService.getByPublicIdForUser(order.getPublicId(), userId);
        return new PaymentSimulationDtos.ChargeResponse(
                order.getPublicId(),
                updated.getState().name(),
                response.providerRef(),
                response.outcome().name(),
                response.message());
    }
}
