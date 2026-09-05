package com.flashreserve.reservation;

import com.flashreserve.catalog.CatalogService;
import com.flashreserve.common.DomainException;
import com.flashreserve.idempotency.IdempotencyService;
import com.flashreserve.security.CurrentUser;
import com.flashreserve.security.FlashUserDetails;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Reservation endpoints. POST is idempotent via the Idempotency-Key header
 * and rate-limited per user (Redis, see RateLimiter).
 */
@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private static final Logger log = LoggerFactory.getLogger(ReservationController.class);

    private final ReservationService reservationService;
    private final CatalogService catalogService;
    private final IdempotencyService idempotencyService;
    private final com.flashreserve.ratelimit.RateLimiter rateLimiter;
    private final com.flashreserve.metrics.FlashMetrics metrics;

    public ReservationController(ReservationService reservationService,
                                 CatalogService catalogService,
                                 IdempotencyService idempotencyService,
                                 com.flashreserve.ratelimit.RateLimiter rateLimiter,
                                 com.flashreserve.metrics.FlashMetrics metrics) {
        this.reservationService = reservationService;
        this.catalogService = catalogService;
        this.idempotencyService = idempotencyService;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
    }

    @PostMapping
    public ResponseEntity<?> create(
            @CurrentUser FlashUserDetails user,
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idemKey,
            @Valid @RequestBody ReservationDtos.CreateReservationRequest request) {

        // ---- rate limiting (per user) ----
        if (!rateLimiter.tryAcquire("reservations", user.getUserId())) {
            throw new DomainException(DomainException.ErrorCode.RATE_LIMIT_EXCEEDED,
                    "Reservation rate limit exceeded; retry shortly");
        }

        return metrics.timeReservation(() -> doCreate(user, idemKey, request));
    }

    private ResponseEntity<?> doCreate(FlashUserDetails user, String idemKey,
                                       ReservationDtos.CreateReservationRequest request) {

        // ---- idempotency ----
        String requestHash = idempotencyService.fingerprint(request);
        boolean idempotent = idemKey != null && !idemKey.isBlank();

        IdempotencyService.Fresh fresh = null;
        if (idempotent) {
            IdempotencyService.Precheck pre = idempotencyService.begin(
                    user.getUserId(), "CREATE_RESERVATION", idemKey, requestHash);
            if (pre instanceof IdempotencyService.Replay replay) {
                metrics.idempotentReplay();
                return ResponseEntity.status(replay.status())
                        .header("X-Idempotent-Replay", "true")
                        .body(replay.bodyJson());
            }
            if (pre instanceof IdempotencyService.InFlight) {
                // Original still executing — client should retry the same key.
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .header("Retry-After", "1")
                        .body(java.util.Map.of(
                                "code", "REQUEST_IN_PROGRESS",
                                "message", "Original request with this key is still executing"));
            }
            fresh = (IdempotencyService.Fresh) pre;
        }

        // ---- business transaction ----
        try {
            var event = catalogService.getEvent(request.eventId());
            Reservation created = reservationService.create(
                    user.getUserId(), event, request.section(), request.quantity());
            var body = ReservationDtos.ReservationResponse.from(created, request.eventId());

            if (fresh != null) {
                idempotencyService.complete(fresh.claimed(), 201, body);
            }
            metrics.reservationSuccess();
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        } catch (DomainException e) {
            metrics.reservationError();
            if (e.getCode() == DomainException.ErrorCode.INVENTORY_UNAVAILABLE) {
                metrics.inventoryUnavailable();
            }
            if (fresh != null) {
                idempotencyService.release(fresh.claimed());
            }
            throw e;
        } catch (Exception e) {
            metrics.reservationError();
            if (fresh != null) {
                idempotencyService.release(fresh.claimed());
            }
            throw e;
        }
    }

    @GetMapping("/{id}")
    public ReservationDtos.ReservationResponse get(@CurrentUser FlashUserDetails user,
                                                   @PathVariable UUID id) {
        Reservation r = reservationService.getByPublicIdForUser(id, user.getUserId());
        return ReservationDtos.ReservationResponse.from(r, resolveEventPublicId(r));
    }

    @GetMapping
    public List<ReservationDtos.ReservationResponse> listMine(@CurrentUser FlashUserDetails user) {
        return reservationService.listForUser(user.getUserId()).stream()
                .map(r -> ReservationDtos.ReservationResponse.from(r, resolveEventPublicId(r)))
                .toList();
    }

    @PostMapping("/{id}/cancel")
    public ReservationDtos.ReservationResponse cancel(@CurrentUser FlashUserDetails user,
                                                       @PathVariable UUID id) {
        Reservation cancelled = reservationService.cancel(id, user.getUserId());
        return ReservationDtos.ReservationResponse.from(cancelled,
                resolveEventPublicId(cancelled));
    }

    private UUID resolveEventPublicId(Reservation r) {
        return catalogService.findEventByDbId(r.getEventId())
                .map(com.flashreserve.catalog.Event::getPublicId)
                .orElse(null);
    }
}
