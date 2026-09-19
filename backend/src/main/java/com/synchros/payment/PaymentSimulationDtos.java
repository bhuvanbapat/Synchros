package com.Synchros.payment;

import java.util.UUID;

public class PaymentSimulationDtos {

    /** Response of the client-driven charge+relay flow. */
    public record ChargeResponse(
            UUID orderId,
            String orderState,
            String providerRef,
            String outcome,
            String message) {
    }
}
