package com.Synchros.reservation;

import com.Synchros.audit.AuditService;
import com.Synchros.common.DomainException;
import com.Synchros.common.NotFoundException;
import com.Synchros.config.SynchrosProperties;
import com.Synchros.outbox.OutboxService;
import com.Synchros.inventory.InventoryPool;
import com.Synchros.inventory.InventoryPoolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Core reservation engine.
 *
 * OVERSELL PREVENTION (see docs/CONCURRENCY.md):
 * The single authoritative mutation is
 *   UPDATE inventory_pool SET available = available - :qty
 *   WHERE id = :id AND available >= :qty
 * executed inside the same transaction as the reservation INSERT.
 *
 * Why this is airtight:
 *  1. PostgreSQL executes an UPDATE atomically: it takes an exclusive row
 *     lock, re-evaluates the predicate under that lock, and only mutates if
 *     it holds. Two concurrent transactions cannot both see available >= qty
 *     and both decrement — the second one's predicate evaluation happens
 *     after the first commits (or blocks on its lock, then re-checks).
 *  2. The CHECK constraint (available >= 0 AND available <= total) is a
 *     database-level backstop: even a logic bug cannot push the counter
 *     negative — Postgres rejects the write.
 *  3. The reservation row and the inventory mutation commit atomically,
 *     so a hold can never exist without its decrement (and vice versa).
 *
 * RELEASE paths (expire/cancel) use conditional UPDATEs on the reservation
 * (confirmIfHeld / expireIfHeld / cancelIfHeld) so the confirm-vs-expire
 * race admits exactly one winner; the loser observes 0 rows affected and
 * aborts its paired inventory compensation.
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ReservationRepository reservationRepository;
    private final ReservationLockRepository lockRepository;
    private final InventoryPoolRepository poolRepository;
    private final OutboxService outboxService;
    private final AuditService auditService;
    private final com.Synchros.metrics.SynchrosMetrics metrics;
    private final SynchrosProperties props;

    public ReservationService(ReservationRepository reservationRepository,
                              ReservationLockRepository lockRepository,
                              InventoryPoolRepository poolRepository,
                              OutboxService outboxService,
                              AuditService auditService,
                              com.Synchros.metrics.SynchrosMetrics metrics,
                              SynchrosProperties props) {
        this.reservationRepository = reservationRepository;
        this.lockRepository = lockRepository;
        this.poolRepository = poolRepository;
        this.outboxService = outboxService;
        this.auditService = auditService;
        this.metrics = metrics;
        this.props = props;
    }

    /**
     * Internal create implementation (called with a resolved pool row).
     * Runs in ONE transaction:
     *   conditional decrement -> reservation INSERT -> outbox INSERT -> audit.
     * Either all of it commits, or none of it does.
     *
     * The reservation's section is derived from the pool row (authoritative),
     * never from the caller's string, so release/reconciliation can always
     * match reservations back to their pools.
     */
    @Transactional
    public Reservation createReservationInternal(Long userId, Long eventId, Long poolId,
                                                  String sectionHint, int quantity) {
        // Resolve the pool to get the authoritative section label.
        InventoryPool pool = poolRepository.findById(poolId)
                .orElseThrow(() -> new NotFoundException("Inventory pool not found: " + poolId));
        String section = pool.getSection();
        Instant now = Instant.now();
        Instant holdExpiresAt = now.plusSeconds(props.getHoldDurationSeconds());

        // 1) Atomic conditional decrement — 0 rows => insufficient inventory.
        int updated = poolRepository.decrementAvailable(poolId, quantity);
        if (updated == 0) {
            throw new DomainException(DomainException.ErrorCode.INVENTORY_UNAVAILABLE,
                    "Requested inventory is no longer available for section " + section);
        }

        // 2) Persist the hold in the same transaction.
        Reservation reservation = new Reservation(userId, eventId, section,
                quantity, now, holdExpiresAt);
        reservationRepository.save(reservation);

        // 3) Outbox event (same transaction — cannot be lost on commit).
        outboxService.append("ReservationCreated", "reservation",
                reservation.getPublicId().toString(),
                java.util.Map.of(
                        "reservationId", reservation.getPublicId().toString(),
                        "userId", userId,
                        "eventId", eventId,
                        "section", section,
                        "quantity", quantity,
                        "holdExpiresAt", holdExpiresAt.toString()));

        // 4) Audit.
        auditService.record("user:" + userId, "RESERVATION_CREATED",
                "reservation", reservation.getPublicId().toString(),
                java.util.Map.of("section", section, "quantity", quantity));

        log.info("reservation created id={} user={} section={} qty={} expiresAt={}",
                reservation.getPublicId(), userId, section, quantity, holdExpiresAt);
        return reservation;
    }

    /** Public typed create (validates section/pool by public event id). */
    @Transactional
    public Reservation create(Long userId, com.Synchros.catalog.Event event,
                               String section, int quantity) {
        // Event-state gate: only SCHEDULED/ON_SALE events are reservable.
        // The DB CHECK constraint defines the vocabulary; the engine now
        // enforces it (previously CANCELLED/SOLD_OUT/CONCLUDED events
        // remained bookable — found in the honest-limitations audit).
        String state = event.getState();
        if (!"SCHEDULED".equals(state) && !"ON_SALE".equals(state)) {
            throw new DomainException(DomainException.ErrorCode.EVENT_NOT_RESERVABLE,
                    "Event is not open for reservations (state=" + state + ")");
        }
        // Sales-window gate: on_sale_at in the future means no holds yet.
        if (event.getOnSaleAt() != null && event.getOnSaleAt().isAfter(Instant.now())) {
            throw new DomainException(DomainException.ErrorCode.EVENT_NOT_RESERVABLE,
                    "Sales for this event have not opened yet");
        }
        InventoryPool pool = poolRepository
                .findByEventIdAndSection(event.getId(), section)
                .orElseThrow(() -> new NotFoundException(
                        "Section not found for event: " + section));
        return createReservationInternal(userId, event.getId(), pool.getId(),
                section, quantity);
    }

    /**
     * Confirm a HELD reservation (invoked after successful payment).
     * Locks the reservation row FOR UPDATE so it serializes with the
     * expiration job; the state machine guard then decides legally.
     */
    @Transactional
    public Reservation confirm(UUID reservationPublicId, Long userId) {
        Reservation locked = lockByPublicIdOwned(reservationPublicId, userId);

        if (locked.getState() == Reservation.State.CONFIRMED) {
            // Idempotent re-confirm (e.g. duplicate payment callback): no-op.
            return locked;
        }

        // Map the illegal-transition guard to a domain error: a confirm that
        // races a lost expiry must surface RESERVATION_EXPIRED (409), not an
        // unhandled IllegalStateException (500). A throw still aborts the tx.
        try {
            locked.transitionTo(Reservation.State.CONFIRMED, Instant.now());
        } catch (IllegalStateException e) {
            throw new DomainException(
                    locked.getState() == Reservation.State.EXPIRED
                            ? DomainException.ErrorCode.RESERVATION_EXPIRED
                            : DomainException.ErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot confirm reservation in state " + locked.getState());
        }

        outboxService.append("ReservationConfirmed", "reservation",
                locked.getPublicId().toString(),
                java.util.Map.of(
                        "reservationId", locked.getPublicId().toString(),
                        "userId", locked.getUserId(),
                        "eventId", locked.getEventId(),
                        "section", locked.getSection(),
                        "quantity", locked.getQuantity()));

        auditService.record("user:" + userId, "RESERVATION_CONFIRMED",
                "reservation", locked.getPublicId().toString(), java.util.Map.of());

        log.info("reservation confirmed id={}", locked.getPublicId());
        return locked;
    }

    /**
     * Cancel a HELD reservation and return inventory.
     */
    @Transactional
    public Reservation cancel(UUID reservationPublicId, Long userId) {
        Reservation locked = lockByPublicIdOwned(reservationPublicId, userId);

        if (locked.getState() == Reservation.State.CONFIRMED) {
            throw new DomainException(
                    DomainException.ErrorCode.RESERVATION_ALREADY_CONFIRMED,
                    "Cannot cancel a confirmed reservation");
        }
        if (locked.isTerminal()) {
            // Already expired/cancelled — treat as idempotent no-op returning
            // current state so client retries after expiry don't 500.
            return locked;
        }

        try {
            locked.transitionTo(Reservation.State.CANCELLED, Instant.now());
        } catch (IllegalStateException e) {
            throw new DomainException(
                    DomainException.ErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot cancel reservation in state " + locked.getState());
        }
        releaseInventory(locked);

        outboxService.append("ReservationCancelled", "reservation",
                locked.getPublicId().toString(),
                java.util.Map.of("reservationId", locked.getPublicId().toString(),
                        "userId", locked.getUserId()));
        auditService.record("user:" + userId, "RESERVATION_CANCELLED",
                "reservation", locked.getPublicId().toString(), java.util.Map.of());
        log.info("reservation cancelled id={}", locked.getPublicId());
        return locked;
    }

    /**
     * Expire a single HELD reservation whose TTL lapsed. Called by the
     * scheduled job. Conditional single-statement state flip wins races
     * atomically: if confirm() flipped it first, this UPDATE affects 0 rows
     * and we must NOT touch inventory.
     */
    @Transactional
    public boolean expireOne(Reservation reservation) {
        Long id = reservation.getId();
        Instant now = Instant.now();

        int flipped = reservationRepository.expireIfHeld(id, now);
        if (flipped == 0) {
            // Someone confirmed (or otherwise transitioned) between the scan
            // and this transaction. Do NOT release inventory.
            log.debug("expire lost race for reservation {}", reservation.getPublicId());
            return false;
        }

        releaseInventory(reservation);

        outboxService.append("ReservationExpired", "reservation",
                reservation.getPublicId().toString(),
                java.util.Map.of("reservationId", reservation.getPublicId().toString(),
                        "userId", reservation.getUserId(),
                        "eventId", reservation.getEventId(),
                        "section", reservation.getSection(),
                        "quantity", reservation.getQuantity()));
        auditService.record("system:expiration", "RESERVATION_EXPIRED",
                "reservation", reservation.getPublicId().toString(), java.util.Map.of());
        metrics.holdExpired();
        log.info("reservation expired id={}", reservation.getPublicId());
        return true;
    }

    /** Return held units to the pool. CHECK constraint guarantees no over-release. */
    private void releaseInventory(Reservation r) {
        poolRepository.findByEventIdAndSection(r.getEventId(), r.getSection())
                .ifPresentOrElse(
                        pool -> poolRepository.incrementAvailable(pool.getId(), r.getQuantity()),
                        () -> log.error("pool missing for event={} section={} — reconciliation will flag",
                                r.getEventId(), r.getSection()));
    }

    private Reservation lockByPublicIdOwned(UUID publicId, Long userId) {
        Reservation locked = lockRepository.lockByPublicId(publicId)
                .orElseThrow(() -> new NotFoundException("Reservation not found: " + publicId));
        if (!locked.getUserId().equals(userId)) {
            throw new DomainException(DomainException.ErrorCode.FORBIDDEN,
                    "Reservation belongs to another user");
        }
        return locked;
    }

    public Reservation getByPublicIdForUser(UUID publicId, Long userId) {
        Reservation r = reservationRepository.findByPublicId(publicId)
                .orElseThrow(() -> new NotFoundException("Reservation not found: " + publicId));
        if (!r.getUserId().equals(userId)) {
            throw new DomainException(DomainException.ErrorCode.FORBIDDEN,
                    "Reservation belongs to another user");
        }
        return r;
    }

    /** System read without ownership check (expiration job, tests). */
    public Reservation getByPublicIdForUserForSystem(UUID publicId) {
        return reservationRepository.findByPublicId(publicId)
                .orElseThrow(() -> new NotFoundException("Reservation not found: " + publicId));
    }

    public List<Reservation> listForUser(Long userId) {
        return reservationRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public List<Reservation> findExpiredHeld(Instant cutoff, int limit) {
        return reservationRepository.findByStateAndHoldExpiresAtBefore(
                Reservation.State.HELD, cutoff,
                org.springframework.data.domain.Limit.of(limit));
    }
}
