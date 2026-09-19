package com.Synchros.order;

import com.Synchros.common.DomainException;
import com.Synchros.payment.MockPaymentGateway;
import com.Synchros.payment.PaymentRelay;
import com.Synchros.payment.PaymentSimulationDtos;
import com.Synchros.security.CurrentUser;
import com.Synchros.security.SynchrosUserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final PaymentRelay paymentRelay;

    public OrderController(OrderService orderService, PaymentRelay paymentRelay) {
        this.orderService = orderService;
        this.paymentRelay = paymentRelay;
    }

    public record CreateOrderRequest(@NotNull UUID reservationId) {
    }

    public record OrderResponse(UUID id, UUID reservationId, String state,
                                int amountCents, String currency, String createdAt) {
        static OrderResponse from(Order o) {
            return new OrderResponse(o.getPublicId(), null, o.getState().name(),
                    o.getAmountCents(), o.getCurrency(), o.getCreatedAt().toString());
        }
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(@CurrentUser SynchrosUserDetails user,
                                                @Valid @RequestBody CreateOrderRequest request) {
        Order order = orderService.createOrderForReservation(
                request.reservationId(), user.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(OrderResponse.from(order));
    }

    @GetMapping("/{id}")
    public OrderResponse get(@CurrentUser SynchrosUserDetails user, @PathVariable UUID id) {
        return OrderResponse.from(orderService.getByPublicIdForUser(id, user.getUserId()));
    }

    @GetMapping
    public List<OrderResponse> listMine(@CurrentUser SynchrosUserDetails user) {
        return orderService.listForUser(user.getUserId()).stream()
                .map(OrderResponse::from).toList();
    }

    /**
     * Simulated "checkout": client asks to charge the order via the mock
     * gateway, then relays the outcome to the webhook (which is where
     * idempotency actually lives). This keeps the REAL payment path
     * (webhook-driven) exercised by the demo.
     */
    @PostMapping("/{id}/pay")
    public ResponseEntity<PaymentSimulationDtos.ChargeResponse> pay(
            @CurrentUser SynchrosUserDetails user,
            @PathVariable UUID id) {

        Order order = orderService.getByPublicIdForUser(id, user.getUserId());
        var outcome = paymentRelay.chargeAndDeliver(order, user.getUserId());
        return ResponseEntity.ok(outcome);
    }
}
