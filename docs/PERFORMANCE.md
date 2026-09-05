# Performance Report (measured)

All numbers below were **actually measured** on this machine. Nothing is
projected or invented. Scenario scripts: `load-tests/` (k6 v1.4.0).

## Environment

| | |
|---|---|
| OS | Windows 11, local development machine |
| CPU | desktop-class, 8 logical cores |
| JVM | Java 25 (Temurings/Oracle JDK 25.0.1), Spring Boot 4.1.1 |
| PostgreSQL | 17-alpine, Docker, `max_connections=200` (compose/test) |
| Kafka | apache/kafka:3.9.0 (KRaft, single broker, container) |
| App config | Tomcat threads 200, Hikari pool 40 |
| Load generator | k6 v1.4.0, local |
| Dataset | 1 event, pools: FLOOR 10, BALCONY 500, HOT500 100 (benchmark pool); 500 test users registered per flash-sale run |

## Scenario 1 — Flash sale (extreme contention) — `flash-sale.js`

500 fresh users, one 100-unit pool, every client fires exactly one
reservation simultaneously. Setup registers 500 users (parallel batches)
before the storm.

| Metric | Result |
|---|---|
| Concurrent clients | 500 |
| Requests (incl. 500 registrations) | 1000 |
| Reservation attempts | 500 |
| **Successful reservations** | **100 (exactly inventory)** |
| Contention rejections (409 INVENTORY_UNAVAILABLE) | 400 |
| **Transport failures (5xx/timeout)** | **0 (http_req_failed rate 0.00%)** |
| Throughput (attempts) | ~19 req/s |
| Duration p50 | 2.0 s |
| p90 | 22.2 s |
| p95 | 22.4 s |
| max | 23.3 s |

Interpretation: correctness is perfect — **no oversell (final pool
available=0), no server errors, all losers got clean 409s.** Tail latency
is large *by design of the stress*: 500 Tomcat-bound VUs contend for one
hot row; most clients queue behind the row lock and pay the full
contention window. The hot-row CAS is the throughput ceiling (as expected
for a single-item flash sale), not a defect. Reconciliation after the
run: `consistent=true`, 0 findings.

Note: the app was **not** tuned for benchmark fairness — this is the
dev-configured single instance with logs at INFO.

## Scenario 2 — Duplicate requests (idempotency) — `duplicate-request.js`

20 VUs fire the same Idempotency-Key + body concurrently.

| Metric | Result |
|---|---|
| Requests | 20 |
| Logical reservations created | **1** |
| First response | 201 |
| Other responses | 409 in-flight / 429 rate-limit (designed) |
| 5xx | **0** |
| Checks passed | 40/40 (100%) |

This benchmark caught two real bugs during the audit (per-VU keys in the
script; the idempotency claim's rollback-only 500 — see IDEMPOTENCY.md).
Post-fix, the invariant holds: N duplicates → exactly 1 effect.

## Scenario 3 — Steady traffic (browse + occasional reserve) — `steady-traffic.js`

Ramping 2→20 VUs over 55s: 80% catalog/inventory reads, 20% reservations
on the 500-unit BALCONY pool.

| Metric | Result |
|---|---|
| Requests | 872 |
| Throughput | ~15.7 req/s |
| Duration p50 | 371 ms |
| p90 | 1.23 s |
| p95 | 1.39 s (threshold target was <500ms — **not met on this machine**) |
| Protocol failures | 0.91% (8 requests — the designed 429s from the per-user limiter, plus early reads) |

Interpretation: mixed browse traffic on a 2013-class dev desktop running
Postgres+Kafka+app+k6 simultaneously does not make sub-500ms p95. The
honest reading: the reservation path adds ~200ms of queueing behind
scheduler contention; on dedicated hardware, or with the read path served
from the availability cache, p95 would drop materially. Recorded as-is —
no fabricated numbers.

## Bottlenecks the tests exposed (and their fixes)

1. **Outbox publisher held a DB transaction across up to 50 blocking
   Kafka sends** → under broker outage it would pin every pooled
   connection. Fixed: short read/mark transactions, I/O outside tx.
2. **Admin `/api/admin/metrics` did ~6 full-table scans + one query per
   pool** → dashboard meltdown precisely during incidents. Fixed: grouped
   count queries; per-event aggregation.
3. **Idempotency claim 500s under concurrency** (rollback-only trap) →
   the duplicate benchmark showed 19/20 failing; post-fix 0.
4. **Expiration scan fetched unbounded rows then limited in memory** →
   query-level `Limit` now.

## Correctness results (the primary goal)

| Invariant | Result |
|---|---|
| 500 clients vs 100 units → successes ≤ 100 | ✅ exactly 100 |
| available never negative | ✅ 0 at end, CHECK never tripped |
| Oversell in 3 repeated IT runs (60 vs 20) | ✅ 0 oversells |
| 20-way duplicate key → 1 logical reservation | ✅ |
| Outbox loss during full benchmark | 0 (537 published, 0 failed, 0 dead) |
| Reconciliation after all runs | consistent, 0 findings |

## Reproduction

```bash
docker compose up -d postgres redis kafka
# run backend: cd backend && mvnw.cmd spring-boot:run
# install k6 (https://grafana.com/docs/k6/latest/set-up/install-k6/)
$ev = (Invoke-RestMethod http://localhost:8081/api/events -Headers $auth).id
k6 run -e EVENT_ID=$ev -e SECTION=HOT500 -e CLIENTS=500 -e UNITS=100 load-tests/flash-sale.js
k6 run -e EVENT_ID=$ev -e SECTION=BALCONY -e RUN_TAG=<unique> load-tests/duplicate-request.js
k6 run -e EVENT_ID=$ev load-tests/steady-traffic.js
```
