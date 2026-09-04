package com.flashreserve.reservation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Optional<Reservation> findByPublicId(UUID publicId);

    List<Reservation> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<Reservation> findByEventId(Long eventId);

    /**
     * Expiration scan: HELD reservations whose hold has lapsed.
     * FOR UPDATE is added by the service via a locking repository query
     * (see ReservationLockRepository) so the expiry job and a concurrent
     * confirm() serialize on the reservation row.
     */
    List<Reservation> findByStateAndHoldExpiresAtBefore(Reservation.State state,
                                                        Instant cutoff);

    @Modifying
    @Query("UPDATE Reservation r SET r.state = 'EXPIRED', r.expiredAt = :now " +
           "WHERE r.id = :id AND r.state = 'HELD'")
    int expireIfHeld(@Param("id") Long id, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE Reservation r SET r.state = 'CANCELLED', r.cancelledAt = :now " +
           "WHERE r.id = :id AND r.state = 'HELD'")
    int cancelIfHeld(@Param("id") Long id, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE Reservation r SET r.state = 'CONFIRMED', r.confirmedAt = :now " +
           "WHERE r.id = :id AND r.state = 'HELD'")
    int confirmIfHeld(@Param("id") Long id, @Param("now") Instant now);
}
