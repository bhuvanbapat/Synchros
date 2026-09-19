package com.synchros.reservation;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Pessimistic-lock companion to ReservationRepository.
 *
 * Why an explicit PESSIMISTIC_WRITE on the reservation row:
 * the confirm-vs-expire race is a read-modify-write on the reservation.
 * Conditional UPDATEs (expireIfHeld/confirmIfHeld) already prevent illegal
 * outcomes atomically; the lock is used by the confirm path to serialize
 * against the expiry job deterministically (single round-trip decision
 * under lock rather than retry loops).
 */
public interface ReservationLockRepository extends JpaRepository<Reservation, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r WHERE r.publicId = :publicId")
    Optional<Reservation> lockByPublicId(@Param("publicId") UUID publicId);

    /** Lock + ownership check in one query (order-create path). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r WHERE r.publicId = :publicId AND r.userId = :userId")
    Optional<Reservation> lockByPublicIdAndOwner(@Param("publicId") UUID publicId,
                                                 @Param("userId") Long userId);
}
