package com.flashreserve.payment;

import com.flashreserve.common.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Simulated PSP callback endpoint. Accepts the gateway outcome and applies
 * it idempotently. In a real deployment this would verify an HMAC
 * signature (documented in docs/SECURITY.md §webhooks).
 */
@RestController
@RequestMapping("/api/payment-webhooks")
public class PaymentWebhookController {

    private final PaymentWebhookService webhookService;

    public PaymentWebhookController(PaymentWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    public record WebhookRequest(
            @NotNull UUID orderId,
            @NotBlank String providerRef,
            @NotNull String outcome) {  // SUCCESS | FAILURE | TIMEOUT
    }

    @PostMapping
    public ResponseEntity<?> receive(@Valid @RequestBody WebhookRequest request) {
        MockPaymentGateway.Outcome outcome;
        try {
            outcome = MockPaymentGateway.Outcome.valueOf(request.outcome());
        } catch (IllegalArgumentException e) {
            throw new DomainException(DomainException.ErrorCode.INVALID_REQUEST,
                    "Unknown outcome: " + request.outcome());
        }
        webhookService.handleCallback(request.orderId(), request.providerRef(), outcome);
        return ResponseEntity.ok(Map.of("status", "accepted"));
    }
}
