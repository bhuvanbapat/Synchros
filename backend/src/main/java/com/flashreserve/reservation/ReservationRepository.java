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
     * Expiration scan: HELD reservations whose hold has lapsed, bounded by
     * limit AT THE QUERY (never fetch-unbounded-then-limit-in-memory — a
     * backlog after an app restart must not make every scan a full scan).
     * The state machine guard (plus the conditional expireIfHeld UPDATE)
     * serializes the expiry job against a concurrent confirm().
     */
    List<Reservation> findByStateAndHoldExpiresAtBefore(Reservation.State state,
                                                        Instant cutoff,
                                                        org.springframework.data.domain.Limit limit);

    /** Admin metrics: one grouped count query instead of N full scans. */
    @Query("SELECT r.state, COUNT(r) FROM Reservation r GROUP BY r.state")
    List<Object[]> countByStateGrouped();

    /** Admin metrics: held/sold units per section for one event. */
    @Query("SELECT r.section, r.state, COALESCE(SUM(r.quantity), 0) " +
           "FROM Reservation r WHERE r.eventId = :eventId AND r.state IN :states " +
           "GROUP BY r.section, r.state")
    List<Object[]> sumQuantityByEventGroupedBySectionAndState(
            @Param("eventId") Long eventId,
            @Param("states") java.util.Collection<Reservation.State> states);

    @Modifying
    @Query("UPDATE Reservation r SET r.state = 'EXPIRED', r.expiredAt = :now " +
           "WHERE r.id = :id AND r.state = 'HELD'")
    int expireIfHeld(@Param("id") Long id, @Param("now") Instant now);
}
