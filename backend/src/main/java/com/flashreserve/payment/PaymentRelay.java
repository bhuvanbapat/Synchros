package com.flashreserve.payment;

import com.flashreserve.order.Order;
import com.flashreserve.order.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Bridges the mock gateway and the real webhook path: charges via the
 * gateway, then delivers the outcome to /api/payment-webhooks exactly the
 * way an external PSP would call back. Delivery failures are simulated by
 * simply not calling the webhook (documented in docs/EVENTS.md).
 *
 * Using an internal HTTP call would be more "realistic" but adds a
 * self-referential network dependency; calling the webhook service
 * directly keeps the demo deterministic while exercising the same code
 * path (idempotency, locks, state machines).
 */
@Component
public class PaymentRelay {

    private static final Logger log = LoggerFactory.getLogger(PaymentRelay.class);

    private final MockPaymentGateway gateway;
    private final PaymentWebhookService webhookService;
    private final OrderService orderService;

    public PaymentRelay(MockPaymentGateway gateway,
                       PaymentWebhookService webhookService,
                       OrderService orderService) {
        this.gateway = gateway;
        this.webhookService = webhookService;
        this.orderService = orderService;
    }

    public PaymentSimulationDtos.ChargeResponse chargeAndDeliver(Order order, Long userId) {
        var response = gateway.charge(order.getAmountCents(), order.getPublicId());

        // Deliver via the webhook path (system actor). Duplicate delivery is
        // intentionally possible here — tests replay this to prove
        // idempotency.
        webhookService.handleCallback(order.getPublicId(), response.providerRef(),
                response.outcome());

        Order updated = orderService.getByPublicIdForUser(order.getPublicId(), userId);
        return new PaymentSimulationDtos.ChargeResponse(
                order.getPublicId(),
                updated.getState().name(),
                response.providerRef(),
                response.outcome().name(),
                response.message());
    }
}
