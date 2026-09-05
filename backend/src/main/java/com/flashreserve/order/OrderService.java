package com.flashreserve.order;

import com.flashreserve.audit.AuditService;
import com.flashreserve.common.DomainException;
import com.flashreserve.common.NotFoundException;
import com.flashreserve.outbox.OutboxService;
import com.flashreserve.payment.MockPaymentGateway;
import com.flashreserve.payment.Payment;
import com.flashreserve.payment.PaymentAttempt;
import com.flashreserve.payment.PaymentAttemptRepository;
import com.flashreserve.payment.PaymentRepository;
import com.flashreserve.reservation.Reservation;
import com.flashreserve.reservation.ReservationLockRepository;
import com.flashreserve.reservation.ReservationRepository;
import com.flashreserve.reservation.ReservationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Order lifecycle orchestration.
 *
 * Transaction boundaries (docs/DATABASE.md §transactions):
 *  1. createOrderForReservation: verify HELD hold, insert order + payment
 *     (INITIATED) + outbox OrderCreated — one transaction. Idempotent per
 *     reservation (returns the existing order).
 *  2. applyPaymentResult: lock order FOR UPDATE, apply state machine,
 *     confirm reservation in the SAME transaction, emit outbox events —
 *     one transaction. If the reservation confirm races expiry and loses,
 *     everything rolls back; the caller retries and the duplicate-callback
 *     path records the outcome without double effects.
 *  3. Webhooks are idempotent per providerRef: a callback for a completed
 *     payment only writes a DUPLICATE_CALLBACK attempt row.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private static final int PRICE_PER_UNIT_CENTS = 5000; // $50 flat demo price

    private final OrderRepository orderRepository;
    private final OrderLockRepository lockRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final ReservationService reservationService;
    private final ReservationRepository reservationRepository;
    private final ReservationLockRepository reservationLockRepository;
    private final OutboxService outboxService;
    private final AuditService auditService;

    public OrderService(OrderRepository orderRepository,
                        OrderLockRepository lockRepository,
                        PaymentRepository paymentRepository,
                        PaymentAttemptRepository attemptRepository,
                        ReservationService reservationService,
                        ReservationRepository reservationRepository,
                        ReservationLockRepository reservationLockRepository,
                        OutboxService outboxService,
                        AuditService auditService) {
        this.orderRepository = orderRepository;
        this.lockRepository = lockRepository;
        this.paymentRepository = paymentRepository;
        this.attemptRepository = attemptRepository;
        this.reservationService = reservationService;
        this.reservationRepository = reservationRepository;
        this.reservationLockRepository = reservationLockRepository;
        this.outboxService = outboxService;
        this.auditService = auditService;
    }

    @Transactional
    public Order createOrderForReservation(UUID reservationPublicId, Long userId) {
        // Lock the reservation row FIRST: serializes against confirm/cancel
        // AND the expiration job, so the HELD + TTL checks below are made
        // on a state that cannot change underneath this transaction.
        // (Without the lock, the expiry job could release inventory between
        // the check and the order INSERT — an orphaned PENDING_PAYMENT
        // order on an EXPIRED reservation.)
        Reservation reservation = reservationLockRepository
                .lockByPublicIdAndOwner(reservationPublicId, userId)
                .orElseThrow(() -> new NotFoundException(
                        "Reservation not found: " + reservationPublicId));

        if (reservation.getState() == Reservation.State.CONFIRMED) {
            throw new DomainException(
                    DomainException.ErrorCode.RESERVATION_ALREADY_CONFIRMED,
                    "Reservation already confirmed; order exists");
        }
        if (reservation.getState() != Reservation.State.HELD) {
            throw new DomainException(DomainException.ErrorCode.RESERVATION_EXPIRED,
                    "Reservation hold is no longer active (state=" + reservation.getState() + ")");
        }

        // Recheck expiry inside the transaction — the hold must still be live.
        if (reservation.getHoldExpiresAt().isBefore(Instant.now())) {
            throw new DomainException(DomainException.ErrorCode.RESERVATION_EXPIRED,
                    "Reservation hold expired at " + reservation.getHoldExpiresAt());
        }

        Order existing = orderRepository.findByReservationId(reservation.getId()).orElse(null);
        if (existing != null) {
            // Idempotent create: same reservation -> same order returned.
            return existing;
        }

        int amount = PRICE_PER_UNIT_CENTS * reservation.getQuantity();
        Order order = new Order(userId, reservation.getId(), amount, "USD");
        orderRepository.save(order);

        Payment payment = new Payment(order.getId(), amount);
        paymentRepository.save(payment);

        outboxService.append("OrderCreated", "order", order.getPublicId().toString(),
                Map.of("orderId", order.getPublicId().toString(),
                        "userId", userId,
                        "reservationId", reservation.getPublicId().toString(),
                        "amountCents", amount));

        auditService.record("user:" + userId, "ORDER_CREATED", "order",
                order.getPublicId().toString(), Map.of("amountCents", amount));

        log.info("order created id={} reservation={} amount={}",
                order.getPublicId(), reservationPublicId, amount);
        return order;
    }

    /**
     * Apply a gateway outcome to an order. SUCCESS confirms the order AND
     * the underlying reservation (single transaction); FAILURE marks the
     * order FAILED and cancels the reservation, releasing inventory.
     * Duplicate results for the same payment are absorbed idempotently.
     */
    @Transactional
    public Order applyPaymentResult(UUID orderPublicId, Long userIdOrNull,
                                    String providerRef,
                                    MockPaymentGateway.Outcome outcome) {
        Order order = lockRepository.lockByPublicId(orderPublicId)
                .orElseThrow(() -> new NotFoundException("Order not found: " + orderPublicId));

        // Webhook path passes null (system); user path enforces ownership.
        if (userIdOrNull != null && !userIdOrNull.equals(order.getUserId())) {
            throw new DomainException(DomainException.ErrorCode.FORBIDDEN,
                    "Order belongs to another user");
        }

        // Late arrival: the order already left PENDING_PAYMENT (e.g. the hold
        // TTL lapsed and the expiration job closed the order) before the
        // gateway outcome arrived. The charge is acknowledged but has NO
        // business effect: payment is marked TIMED_OUT (a real deployment
        // would trigger a refund), the attempt is recorded, and the current
        // order state is returned — never a 500.
        if (order.getState() != Order.State.PENDING_PAYMENT) {
            Payment open = paymentRepository.findByOrderId(order.getId()).stream()
                    .filter(p -> p.getState() == Payment.State.INITIATED)
                    .findFirst().orElse(null);
            if (open != null) {
                open.complete(Payment.State.TIMED_OUT, providerRef, Instant.now());
                attemptRepository.save(new PaymentAttempt(open.getId(),
                        "LATE_CALLBACK", providerRef));
            } else {
                Payment any = paymentRepository.findByOrderId(order.getId()).stream()
                        .findFirst().orElse(null);
                if (any != null) {
                    attemptRepository.save(new PaymentAttempt(any.getId(),
                            "DUPLICATE_CALLBACK", providerRef));
                }
            }
            log.info("late payment callback absorbed order={} state={} ref={}",
                    orderPublicId, order.getState(), providerRef);
            return order;
        }

        Payment payment = paymentRepository
                .findByOrderId(order.getId()).stream()
                .filter(p -> p.getState() == Payment.State.INITIATED)
                .findFirst()
                .orElse(null);

        // Idempotency: payment already terminal => record duplicate only.
        if (payment == null) {
            Payment any = paymentRepository.findByOrderId(order.getId()).stream()
                    .findFirst().orElseThrow(() -> new DomainException(
                            DomainException.ErrorCode.INVALID_STATE_TRANSITION,
                            "No payment for order " + orderPublicId));
            attemptRepository.save(new PaymentAttempt(any.getId(),
                    "DUPLICATE_CALLBACK", providerRef));
            log.info("duplicate payment callback absorbed order={} ref={}",
                    orderPublicId, providerRef);
            return order;
        }

        Instant now = Instant.now();

        switch (outcome) {
            case SUCCESS -> {
                payment.complete(Payment.State.SUCCEEDED, providerRef, now);
                attemptRepository.save(new PaymentAttempt(payment.getId(), "SUCCESS", providerRef));
                order.transitionTo(Order.State.CONFIRMED, now);

                // Confirm the reservation in the SAME transaction. If the
                // hold expired first, confirm() throws and everything rolls
                // back — consistent state, retry-safe.
                Reservation reservation = reservationRepository
                        .findById(order.getReservationId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Order references missing reservation"));
                reservationService.confirm(reservation.getPublicId(), order.getUserId());

                outboxService.append("PaymentSucceeded", "payment",
                        payment.getPublicId().toString(),
                        Map.of("orderId", order.getPublicId().toString(),
                                "providerRef", providerRef,
                                "amountCents", payment.getAmountCents()));
                outboxService.append("OrderConfirmed", "order",
                        order.getPublicId().toString(),
                        Map.of("orderId", order.getPublicId().toString(),
                                "userId", order.getUserId()));
                auditService.record("payment-gateway", "PAYMENT_SUCCEEDED", "order",
                        order.getPublicId().toString(), Map.of("providerRef", providerRef));
            }
            case FAILURE -> {
                payment.complete(Payment.State.FAILED, providerRef, now);
                attemptRepository.save(new PaymentAttempt(payment.getId(), "FAILURE", providerRef));
                order.transitionTo(Order.State.FAILED, now);

                // Cancel the held reservation and release inventory.
                Reservation reservation = reservationRepository
                        .findById(order.getReservationId()).orElse(null);
                if (reservation != null && reservation.getState() == Reservation.State.HELD) {
                    reservationService.cancel(reservation.getPublicId(), order.getUserId());
                }

                outboxService.append("PaymentFailed", "payment",
                        payment.getPublicId().toString(),
                        Map.of("orderId", order.getPublicId().toString(),
                                "providerRef", providerRef));
                auditService.record("payment-gateway", "PAYMENT_FAILED", "order",
                        order.getPublicId().toString(),
                        Map.of("providerRef", providerRef));
            }
            case TIMEOUT -> {
                // A gateway timeout is NOT a terminal outcome — the charge
                // may still complete and call back. The payment stays
                // INITIATED (retryable: a client retry or a late webhook
                // finds the open attempt and applies the definitive
                // outcome); the hold TTL remains the safety net that
                // eventually releases inventory if nothing arrives.
                attemptRepository.save(new PaymentAttempt(payment.getId(), "TIMEOUT", providerRef));
                outboxService.append("PaymentTimedOut", "payment",
                        payment.getPublicId().toString(),
                        Map.of("orderId", order.getPublicId().toString()));
                auditService.record("payment-gateway", "PAYMENT_TIMEOUT", "order",
                        order.getPublicId().toString(), Map.of("providerRef", providerRef));
            }
        }
        return order;
    }

    public Order getByPublicIdForUser(UUID publicId, Long userId) {
        Order o = orderRepository.findByPublicId(publicId)
                .orElseThrow(() -> new NotFoundException("Order not found: " + publicId));
        if (!o.getUserId().equals(userId)) {
            throw new DomainException(DomainException.ErrorCode.FORBIDDEN,
                    "Order belongs to another user");
        }
        return o;
    }

    public List<Order> listForUser(Long userId) {
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /** System-path expiry used when a hold lapses with an open order. */
    @Transactional
    public boolean expireIfPending(Long orderDbId) {
        return orderRepository.expireIfPending(orderDbId) == 1;
    }
}
