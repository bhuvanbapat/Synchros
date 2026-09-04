package com.flashreserve.order;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Pessimistic-lock companion to OrderRepository. Payment results (possibly
 * duplicated) and the expiration job both mutate orders; the webhook path
 * takes the row lock so concurrent duplicate callbacks serialize and the
 * second one observes the first's terminal state.
 */
public interface OrderLockRepository extends JpaRepository<Order, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.publicId = :publicId")
    Optional<Order> lockByPublicId(@Param("publicId") UUID publicId);
}
