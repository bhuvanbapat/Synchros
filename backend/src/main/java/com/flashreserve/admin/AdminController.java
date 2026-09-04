package com.flashreserve.admin;

import com.flashreserve.analytics.AnalyticsRepository;
import com.flashreserve.audit.AuditRepository;
import com.flashreserve.inventory.InventoryPool;
import com.flashreserve.inventory.InventoryPoolRepository;
import com.flashreserve.kafka.DeadLetter;
import com.flashreserve.kafka.DeadLetterRepository;
import com.flashreserve.outbox.OutboxEvent;
import com.flashreserve.outbox.OutboxRepository;
import com.flashreserve.outbox.OutboxService;
import com.flashreserve.reconciliation.ReconciliationService;
import com.flashreserve.reservation.Reservation;
import com.flashreserve.reservation.ReservationRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Operations API (ADMIN role only, enforced by SecurityConfig).
 * Functional, not decorative: real counts from real tables.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final InventoryPoolRepository poolRepository;
    private final ReservationRepository reservationRepository;
    private final OutboxRepository outboxRepository;
    private final DeadLetterRepository deadLetterRepository;
    private final AuditRepository auditRepository;
    private final AnalyticsRepository analyticsRepository;
    private final ReconciliationService reconciliationService;
    private final com.flashreserve.idempotency.IdempotencyService idempotencyService;

    public AdminController(InventoryPoolRepository poolRepository,
                           ReservationRepository reservationRepository,
                           OutboxRepository outboxRepository,
                           DeadLetterRepository deadLetterRepository,
                           AuditRepository auditRepository,
                           AnalyticsRepository analyticsRepository,
                           ReconciliationService reconciliationService,
                           com.flashreserve.idempotency.IdempotencyService idempotencyService) {
        this.poolRepository = poolRepository;
        this.reservationRepository = reservationRepository;
        this.outboxRepository = outboxRepository;
        this.deadLetterRepository = deadLetterRepository;
        this.auditRepository = auditRepository;
        this.analyticsRepository = analyticsRepository;
        this.reconciliationService = reconciliationService;
        this.idempotencyService = idempotencyService;
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> m = new HashMap<>();

        List<Map<String, Object>> pools = poolRepository.findAll().stream()
                .map(this::poolView).toList();
        m.put("pools", pools);

        Map<String, Long> reservationCounts = new HashMap<>();
        for (Reservation.State s : Reservation.State.values()) {
            reservationCounts.put(s.name(),
                    reservationRepository.findAll().stream()
                            .filter(r -> r.getState() == s).count());
        }
        m.put("reservations", reservationCounts);

        Map<String, Object> outbox = new HashMap<>();
        for (OutboxEvent.State s : OutboxEvent.State.values()) {
            outbox.put(s.name(), outboxRepository.countByState(s));
        }
        m.put("outbox", outbox);

        m.put("deadLetters", deadLetterRepository.count());
        m.put("auditedAt", Instant.now().toString());
        return m;
    }

    @GetMapping("/outbox/failed")
    public List<OutboxService.OutboxDto> failedOutbox() {
        return outboxRepository
                .findByStateOrderByCreatedAtDesc(OutboxEvent.State.FAILED).stream()
                .map(OutboxService.OutboxDto::from).toList();
    }

    @GetMapping("/outbox/dead")
    public List<OutboxService.OutboxDto> deadOutbox() {
        return outboxRepository
                .findByStateOrderByCreatedAtDesc(OutboxEvent.State.DEAD).stream()
                .map(OutboxService.OutboxDto::from).toList();
    }

    @GetMapping("/dead-letters")
    public List<Map<String, Object>> deadLetters() {
        return deadLetterRepository.findTop200ByOrderByCreatedAtDesc().stream()
                .map(dl -> Map.<String, Object>of(
                        "eventId", dl.getEventId() == null ? "" : dl.getEventId().toString(),
                        "eventType", String.valueOf(dl.getEventType()),
                        "topic", String.valueOf(dl.getTopic()),
                        "error", dl.getError(),
                        "attempts", dl.getAttemptCount(),
                        "createdAt", dl.getCreatedAt().toString()))
                .toList();
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit(@RequestParam(defaultValue = "50") int limit) {
        int capped = Math.min(Math.max(limit, 1), 200);
        return auditRepository.findTop200ByOrderByCreatedAtDesc().stream()
                .limit(capped)
                .map(a -> Map.<String, Object>of(
                        "actor", a.getActor(),
                        "operation", a.getOperation(),
                        "entity", a.getEntityType() + ":" + a.getEntityId(),
                        "result", a.getResult(),
                        "createdAt", a.getCreatedAt().toString()))
                .toList();
    }

    @PostMapping("/reconciliation")
    public ResponseEntity<ReconciliationService.ReconciliationReport> runReconciliation() {
        return ResponseEntity.ok(reconciliationService.reconcile());
    }

    @PostMapping("/maintenance/purge-expired-idempotency")
    public Map<String, Object> purgeIdempotency() {
        return Map.of("purged", idempotencyService.purgeExpired());
    }

    @GetMapping("/analytics")
    public Map<String, Object> analytics() {
        Map<String, Object> m = new HashMap<>();
        m.put("reservationEvents", analyticsRepository
                .countByCategoryGroupedByType("RESERVATION").stream()
                .map(row -> Map.of("type", row[0], "count", row[1]))
                .toList());
        m.put("orderEvents", analyticsRepository
                .countByCategoryGroupedByType("ORDER").stream()
                .map(row -> Map.of("type", row[0], "count", row[1]))
                .toList());
        m.put("paymentEvents", analyticsRepository
                .countByCategoryGroupedByType("PAYMENT").stream()
                .map(row -> Map.of("type", row[0], "count", row[1]))
                .toList());
        return m;
    }

    private Map<String, Object> poolView(InventoryPool p) {
        Map<String, Object> v = new HashMap<>();
        v.put("id", p.getPublicId().toString());
        v.put("eventId", p.getEventId());
        v.put("section", p.getSection());
        v.put("total", p.getTotal());
        v.put("available", p.getAvailable());
        v.put("held", p.getTotal() - p.getAvailable() - soldForPool(p));
        v.put("sold", soldForPool(p));
        return v;
    }

    private int soldForPool(InventoryPool p) {
        return reservationRepository.findByEventId(p.getEventId()).stream()
                .filter(r -> r.getSection().equals(p.getSection()))
                .filter(r -> r.getState() == Reservation.State.CONFIRMED)
                .mapToInt(Reservation::getQuantity)
                .sum();
    }
}
