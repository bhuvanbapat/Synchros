package com.flashreserve.payment;

import tools.jackson.databind.ObjectMapper;
import com.flashreserve.common.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Simulated PSP callback endpoint. Accepts the gateway outcome and applies
 * it idempotently. Every delivery MUST carry an HMAC-SHA256 signature over
 * the exact raw request bytes (X-Signature header, base64) — unsigned or
 * badly signed calls are rejected with 401 before any parsing or state
 * change (see docs/SECURITY.md §webhooks).
 */
@RestController
@RequestMapping("/api/payment-webhooks")
public class PaymentWebhookController {

    public static final String SIGNATURE_HEADER = "X-Signature";

    private final PaymentWebhookService webhookService;
    private final WebhookSigner signer;
    private final ObjectMapper objectMapper;

    public PaymentWebhookController(PaymentWebhookService webhookService,
                                    WebhookSigner signer,
                                    ObjectMapper objectMapper) {
        this.webhookService = webhookService;
        this.signer = signer;
        this.objectMapper = objectMapper;
    }

    public record WebhookRequest(
            @NotNull UUID orderId,
            @NotBlank String providerRef,
            @NotNull String outcome) {  // SUCCESS | FAILURE | TIMEOUT
    }

    @PostMapping
    public ResponseEntity<?> receive(@Valid @RequestBody WebhookRequest request,
                                     @org.springframework.web.bind.annotation.RequestHeader(
                                             value = SIGNATURE_HEADER, required = false) String signature,
                                     jakarta.servlet.http.HttpServletRequest httpRequest) {
        // Signature check happens on the RAW bytes — before any business
        // logic runs. A tampered payload fails here, never downstream.
        byte[] rawBody = (byte[]) httpRequest.getAttribute("rawRequestBody");
        if (rawBody == null || !signer.verify(rawBody, signature)) {
            throw new DomainException(DomainException.ErrorCode.WEBHOOK_SIGNATURE_INVALID,
                    "Webhook signature verification failed");
        }

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

    /** Serializes + signs a payload exactly the way the mock PSP would. */
    public record SignedCallback(String json, String signature) {
    }

    public SignedCallback signCallback(WebhookRequest request) throws Exception {
        String json = objectMapper.writeValueAsString(request);
        return new SignedCallback(json, signer.sign(json.getBytes(StandardCharsets.UTF_8)));
    }
}
