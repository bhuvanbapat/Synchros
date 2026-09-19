package com.Synchros.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByPublicId(UUID publicId);

    Optional<Order> findByReservationId(Long reservationId);

    List<Order> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** Reconciliation: only orders that could be orphaned, bounded by state. */
    List<Order> findByState(Order.State state);

    @Modifying
    @Query("UPDATE Order o SET o.state = 'EXPIRED' " +
           "WHERE o.id = :id AND o.state = 'PENDING_PAYMENT'")
    int expireIfPending(@Param("id") Long id);
}
