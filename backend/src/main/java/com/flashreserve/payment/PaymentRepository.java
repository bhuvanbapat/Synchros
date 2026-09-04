package com.flashreserve.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByPublicId(UUID publicId);

    List<Payment> findByOrderId(Long orderId);

    Optional<Payment> findByOrderIdAndState(Long orderId, Payment.State state);
}
