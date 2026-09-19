package com.synchros.admin;

import com.synchros.analytics.AnalyticsRepository;
import com.synchros.audit.AuditRepository;
import com.synchros.inventory.InventoryPool;
import com.synchros.inventory.InventoryPoolRepository;
import com.synchros.kafka.DeadLetter;
import com.synchros.kafka.DeadLetterRepository;
import com.synchros.outbox.OutboxEvent;
import com.synchros.outbox.OutboxRepository;
import com.synchros.outbox.OutboxService;
import com.synchros.reconciliation.ReconciliationService;
import com.synchros.reservation.Reservation;
import com.synchros.reservation.ReservationRepository;
import com.synchros.security.CurrentUser;
import com.synchros.security.SynchrosUserDetails;
import com.synchros.user.User;
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
    private final com.synchros.idempotency.IdempotencyService idempotencyService;
    private final com.synchros.user.AccountAdminService accountAdminService;

    public AdminController(InventoryPoolRepository poolRepository,
                           ReservationRepository reservationRepository,
                           OutboxRepository outboxRepository,
                           DeadLetterRepository deadLetterRepository,
                           AuditRepository auditRepository,
                           AnalyticsRepository analyticsRepository,
                           ReconciliationService reconciliationService,
                           com.synchros.idempotency.IdempotencyService idempotencyService,
                           com.synchros.user.AccountAdminService accountAdminService) {
        this.poolRepository = poolRepository;
        this.reservationRepository = reservationRepository;
        this.outboxRepository = outboxRepository;
        this.deadLetterRepository = deadLetterRepository;
        this.auditRepository = auditRepository;
        this.analyticsRepository = analyticsRepository;
        this.reconciliationService = reconciliationService;
        this.idempotencyService = idempotencyService;
        this.accountAdminService = accountAdminService;
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> m = new HashMap<>();

        List<InventoryPool> pools = poolRepository.findAll();
        Map<Long, Map<String, int[]>> heldSoldByEvent = heldSoldByEvent(pools);
        m.put("pools", pools.stream()
                .map(p -> poolView(p, heldSoldByEvent)).toList());

        // One grouped query instead of a full-table scan per state — the
        // ops dashboard must stay cheap even mid-flash-sale.
        Map<String, Long> reservationCounts = new HashMap<>();
        for (Reservation.State s : Reservation.State.values()) {
            reservationCounts.put(s.name(), 0L);
        }
        reservationRepository.countByStateGrouped()
                .forEach(row -> reservationCounts.put(String.valueOf(row[0]),
                        ((Number) row[1]).longValue()));
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

    // ---- account state management (suspension feature completion) ----

    /**
     * Suspend or reactivate a user account by their public id. Suspension
     * binds immediately: every request re-loads the principal, so a
     * suspended user 401s on their next call even with a live token.
     */
    @PostMapping("/users/{userPublicId}/state")
    public Map<String, Object> setUserState(
            @CurrentUser SynchrosUserDetails admin,
            @PathVariable String userPublicId,
            @RequestBody Map<String, String> body) {
        String target = body.get("accountState");
        if (target == null || target.isBlank()) {
            throw new com.synchros.common.DomainException(
                    com.synchros.common.DomainException.ErrorCode.INVALID_REQUEST,
                    "Body must carry accountState: ACTIVE | SUSPENDED");
        }
        User target_ = accountAdminService.setState(
                userPublicId, target.trim().toUpperCase(), admin.getUserId());
        return Map.of(
                "id", target_.getPublicId().toString(),
                "email", target_.getEmail(),
                "accountState", target_.getAccountState());
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

    /**
     * held/sold units per pool, computed with ONE grouped query per event
     * (not one full-table scan per pool — the old N+1 melted under load).
     * All state is local to this request: no shared mutable fields.
     */
    private Map<Long, Map<String, int[]>> heldSoldByEvent(List<InventoryPool> pools) {
        Map<Long, Map<String, int[]>> byEvent = new HashMap<>();
        for (Long eventId : pools.stream().map(InventoryPool::getEventId).distinct().toList()) {
            Map<String, int[]> perSection = new HashMap<>();
            reservationRepository
                    .sumQuantityByEventGroupedBySectionAndState(eventId,
                            List.of(Reservation.State.HELD, Reservation.State.CONFIRMED))
                    .forEach(row -> {
                        String section = (String) row[0];
                        boolean confirmed = "CONFIRMED".equals(String.valueOf(row[1]));
                        int units = ((Number) row[2]).intValue();
                        int[] heldSold = perSection.computeIfAbsent(section,
                                k -> new int[2]);
                        heldSold[confirmed ? 1 : 0] = units;
                    });
            byEvent.put(eventId, perSection);
        }
        return byEvent;
    }

    private Map<String, Object> poolView(InventoryPool p,
                                         Map<Long, Map<String, int[]>> heldSoldByEvent) {
        int[] heldSold = heldSoldByEvent
                .getOrDefault(p.getEventId(), Map.of())
                .getOrDefault(p.getSection(), new int[2]);
        Map<String, Object> v = new HashMap<>();
        v.put("id", p.getPublicId().toString());
        v.put("eventId", p.getEventId());
        v.put("section", p.getSection());
        v.put("total", p.getTotal());
        v.put("available", p.getAvailable());
        v.put("held", heldSold[0]);
        v.put("sold", heldSold[1]);
        return v;
    }
}
