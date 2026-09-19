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

**Business counters** (`SynchrosMetrics`):

| Metric | Meaning |
|---|---|
| `Synchros_reservation_duration` (Timer) | reservation creation latency |
| `Synchros_reservation_outcome{outcome=success}` | successful holds |
| `Synchros_reservation_outcome{outcome=inventory_unavailable}` | content losses (sold out) |
| `Synchros_reservation_outcome{outcome=error}` | unexpected failures |
| `Synchros_idempotent_replays` | cache-served replays (duplicate-traffic share) |
| `Synchros_expired_holds` | TTL expirations (abandonment rate) |

**Rate limit**: `Synchros_rate_limit{outcome=allowed|rejected}`.

**Cache**: `Synchros_cache{name, outcome=hit|miss|error}` — hit ratio
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

## Tracing (correlation IDs always, OTel opt-in)

Request-ID correlation spans API → service → DB/log → outbox → audit. The
same `eventId` identifies an event from outbox row → Kafka message →
processed_event marker, so an event's journey is reconstructable from
tables + logs.

OpenTelemetry is wired but **opt-in** (micrometer-tracing bridge + an
explicit OTLP/HTTP span exporter): set
`OTEL_EXPORTER_OTLP_ENDPOINT=http://collector:4318` — **the endpoint is
the on/off switch**; when it is empty (the default) no exporter bean is
created and the app runs with zero tracing overhead. (A separate enable
flag was deliberately removed: two switches drift out of sync; one
cannot.) The OTLP *metrics* pusher is disabled by default — without that
it POSTs to localhost:4318 every 60s even with no collector configured.
Correlation IDs remain the always-on baseline.

The HA compose stack ships a collector profile (v0.160, OTLP gRPC 4317 +
HTTP 4318, debug exporter writing every span to stdout):

```
docker compose -f docker-compose.ha.yml up -d --profile tracing otel-collector
docker network connect Synchros_default Synchros-otel-collector  # bridge to the main stack
# app .env: OTEL_EXPORTER_OTLP_ENDPOINT=http://Synchros-otel-collector:4318
```

**Verification drill (run live 2026-09-07):** enable
`SPAN_PROBE_ENABLED=true` alongside the endpoint, then
`POST /api/dev/span-probe` with a bearer token. The probe emits one SDK
span directly — if `Synchros.span-probe` appears in
`docker logs Synchros-otel-collector`, exporter wiring is proven
independently of HTTP-observation config. The same drill observed HTTP
`authorize request` and `task outboxPublisher.publishPending` spans,
proving the whole instrumented pipeline end-to-end. The probe endpoint is
JWT-protected and dormant unless explicitly enabled — never turn it on in
production.

## Reconciliation gauges (alertable)

The scheduled sweep (every `Synchros.reconciliation.interval-ms`,
default 5 min) exports two gauges to Prometheus:
`Synchros_reconciliation_findings` (counter — every finding bumps it)
and `Synchros_reconciliation_consistent` (1 = last run clean, 0 =
findings). A minimal alert rule is then:
`Synchros_reconciliation_consistent == 0` for 10m → page. The repo
intentionally ships no dashboards; the gauges are the contract.
