package com.flashreserve.outbox;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Maps event types to topics. Deliberately few topics (ADR-005):
 * domain events share reservation-events / order-events / payment-events.
 */
@Component
public class FlashReserveTopics {

    public static final String RESERVATION_EVENTS = "reservation-events";
    public static final String ORDER_EVENTS = "order-events";
    public static final String PAYMENT_EVENTS = "payment-events";
    public static final String NOTIFICATION_EVENTS = "notification-events";

    private static final Map<String, String> ROUTING = Map.of(
            "ReservationCreated", RESERVATION_EVENTS,
            "ReservationConfirmed", RESERVATION_EVENTS,
            "ReservationCancelled", RESERVATION_EVENTS,
            "ReservationExpired", RESERVATION_EVENTS,
            "OrderCreated", ORDER_EVENTS,
            "OrderConfirmed", ORDER_EVENTS,
            "PaymentSucceeded", PAYMENT_EVENTS,
            "PaymentFailed", PAYMENT_EVENTS,
            "PaymentTimedOut", PAYMENT_EVENTS);

    public String topicFor(String eventType) {
        String topic = ROUTING.get(eventType);
        if (topic == null) {
            throw new IllegalArgumentException("No topic routed for event type " + eventType);
        }
        return topic;
    }
}
