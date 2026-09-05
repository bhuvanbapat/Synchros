# Observability

## Request correlation

`RequestIdFilter` propagates or generates `X-Request-Id`, puts it in the
SLF4J MDC, echoes it on the response, and clears it after. Log pattern
includes `[%X{requestId}]`. Every error response body carries the
`requestId` — a user-reported error is greppable across the whole request
lifecycle. Audit rows persist the requestId at write time.

## Structured logging

One-line events with stable key=value fields (never multi-line dumps,
never secrets/passwords/full payment payloads):

```
reservation created id=UUID user=1 section=FLOOR qty=2 expiresAt=...
outbox published eventId=UUID topic=reservation-events
duplicate payment callback absorbed order=UUID ref=mock-...
message quarantined topic=payment-events eventId=null error=malformed
```

Levels: INFO for business events, WARN for absorbed/retryable failures
(outbox retries, lost races), ERROR for quarantines and unhandled
exceptions (with stack, server-side only).

## Metrics (Actuator + Micrometer, Prometheus format)

`GET /actuator/prometheus` (admin), health/info public.

**HTTP**: `http_server_requests_seconds{uri,method,status}` via Spring Boot
observability — request count, latency histograms, error rate by status.

**Business counters** (`FlashMetrics`):

| Metric | Meaning |
|---|---|
| `flashreserve_reservation_duration` (Timer) | reservation creation latency |
| `flashreserve_reservation_outcome{outcome=success}` | successful holds |
| `flashreserve_reservation_outcome{outcome=inventory_unavailable}` | content losses (sold out) |
| `flashreserve_reservation_outcome{outcome=error}` | unexpected failures |
| `flashreserve_idempotent_replays` | cache-served replays (duplicate-traffic share) |
| `flashreserve_expired_holds` | TTL expirations (abandonment rate) |

**Rate limit**: `flashreserve_rate_limit{outcome=allowed|rejected}`.

**Cache**: `flashreserve_cache{name, outcome=hit|miss|error}` — hit ratio
and (via `error`) Redis degradation are both visible.

**Outbox/Kafka lag**: outbox state counts per PUBLISHED/PENDING/FAILED/DEAD
from `/api/admin/metrics`; a PENDING count that grows while Kafka is up is
the "publisher broken" signal. `spring_kafka_*` producer/consumer metrics
are exposed by Actuator automatically.

## Health

- `GET /actuator/health` (public): liveness with DB detail.
- `GET /api/health` (authenticated): app-level view — DB (`SELECT 1`) and
  Redis ping with **pooled-connection release** (a health endpoint must
  never cause the outage it watches).

## Admin operational views (real data, no decoration)

`/api/admin/metrics` — pool totals/available/held/sold (one grouped query
per event — the earlier per-pool N+1 melted during flash sales), reservation
counts by state, outbox counts by state, dead-letter count.
`/api/admin/outbox/failed|dead`, `/api/admin/dead-letters`,
`/api/admin/audit?limit=`, `/api/admin/analytics` (event-type rollups),
`POST /api/admin/reconciliation`, `POST /api/admin/maintenance/purge-expired-idempotency`.

## Tracing (what exists, what's deferred)

Request-ID correlation spans API → service → DB/log → outbox → audit. The
same `eventId` identifies an event from outbox row → Kafka message →
processed_event marker, so an event's journey is reconstructable from
tables + logs. OpenTelemetry spans are **not** wired: for a single-node
portfolio demo the operational payoff did not justify the fragility —
documented as a deliberate limitation; the correlation IDs make the
critical paths debuggable today.
