# Resume Notes

## One-paragraph description (for portfolios)

FlashReserve is a high-concurrency reservation platform (limited-release
ticketing domain) engineered around one question: *how do you let 500
users fight over 100 units without overselling, double-charging, or losing
an event?* Inventory ownership is strictly transactional in PostgreSQL
(atomic conditional UPDATE + CHECK constraint backstop), every mutation is
idempotent (DB-claimed idempotency keys, HMAC-verified payment webhooks,
consumer-side event dedup), and all business events flow through a
transactional outbox to Kafka with bounded retries and a dead-letter
table. Auth is stateless JWT (HS256, JDK-only, fresh-principal reload so
suspensions bind immediately). Correctness is proven by 55 automated tests
including a 100-clients-vs-10-units oversell test, a 16-thread concurrent
idempotency-claim test, and confirm-vs-expire race rounds — plus measured
k6 benchmarks (500 VUs → exactly 100 successes, 0 errors) and a live
Kafka leader-kill chaos drill (RF=3, zero message loss).

## Stack (actual)

Java 25 · Spring Boot 4.1 (Web, Data JPA, Security, Validation, Actuator) ·
PostgreSQL 17 + Flyway · Redis (Lettuce, Lua token bucket) · Apache Kafka
(KRaft, Spring Kafka) · Micrometer/Prometheus · React 19 + TypeScript +
Vite + vitest · Testcontainers · k6 · Docker Compose · GitHub-ready CI.

## Hardest engineering problem solved

Concurrent duplicate requests with the same idempotency key crashed the
claim path: the `DataIntegrityViolationException` thrown during Hibernate
flush marks the surrounding transaction rollback-only *before* the catch
block runs, so the "graceful conflict" path died at commit with
`UnexpectedRollbackException` — a 500 on every concurrent duplicate,
invisible to sequential tests. Found by firing 20 duplicates under k6,
diagnosed from the commit-time stack trace, fixed by switching the claim
to `INSERT ... ON CONFLICT DO NOTHING` rowcount semantics (no exception
signaling at all), and pinned with a 16-thread regression test.

Second mention: the outbox publisher originally held one DB transaction
open across up to 50 blocking Kafka sends — a Kafka outage would exhaust
the connection pool and take the whole API down with it. Restructured to
short read/mark transactions with all broker I/O outside any transaction.

## Mechanisms in one line each

- **Overselling**: `UPDATE inventory_pool SET available = available - :q
  WHERE id = :id AND available >= :q` — single-statement atomic CAS; the
  CHECK constraint makes negative inventory unrepresentable.
- **Idempotency**: (user, operation, key) UNIQUE row claimed via
  ON CONFLICT DO NOTHING; responses cached for replay; fingerprints (SHA-256)
  reject key reuse with different bodies.
- **Outbox**: event row committed in the business transaction; publisher
  drains to Kafka with bounded retries → DEAD state; consumers dedup by
  (event_id, consumer_group).
- **Expiry vs confirm race**: conditional state flips + pessimistic row
  locks ⇒ exactly one winner; loser rolls back atomically.

## Measured results (no fabrication)

- 500 concurrent clients → 100-unit pool: **exactly 100 successes**, 400
  clean 409s, **0 5xx**, final availability exactly 0, reconciliation
  consistent (0 findings).
- 20 concurrent identical idempotent requests → **exactly 1 reservation**.
- Flash-sale latency p50 2.0s / p95 22.4s under full 500-way contention on
  one dev machine (single hot row is the bottleneck by design).
- Steady mixed traffic: ~15.7 req/s, p95 1.39s (dev machine runs app +
  Postgres + Kafka + k6 together; documented honestly).
- **Kafka HA chaos drill**: killed the events-topic leader in a 3-broker
  RF=3 cluster → leadership failed over, ISR 3→2, 10/10 messages
  survived, broker rejoined after restart (docker-compose.ha.yml).
- **Live security smoke**: JWT login → full reserve→order→signed-webhook
  pay flow; unsigned + tampered webhooks and tampered JWTs all 401;
  reconciliation consistent, 0 findings (tools/smoke.ps1).
- 55 backend tests + 5 frontend tests, all green.

## Resume bullets (ready to paste)

- Built a high-concurrency reservation platform (Java/Spring Boot,
  PostgreSQL, Kafka, Redis) where inventory overselling is impossible by
  construction: atomic conditional updates with DB CHECK-constraint
  backstops, proven by automated 100-vs-10 oversell tests and 500-VU k6
  benchmarks with zero oversells and zero 5xx responses.
- Implemented exactly-once-effect semantics across the request path —
  DB-claimed idempotency keys with request fingerprinting and cached
  replays, HMAC-SHA256-verified payment webhooks (byte-exact raw-body
  signatures, constant-time compare), and Kafka consumer dedup —
  and diagnosed/fixed a subtle transaction rollback-only bug in the
  concurrent idempotency claim (Hibernate flush violation → commit-time
  UnexpectedRollbackException) via load testing.
- Designed a transactional outbox (bounded retries, dead-letter state)
  with all broker I/O outside database transactions, so a Kafka outage
  never blocks business traffic; verified event delivery with live
  failure drills and a 3-broker leader-kill chaos run (RF=3,
  min.insync.replicas=2, zero message loss), plus stateless JWT auth
  (HS256, JDK-only, per-request principal reload) across the API.


## Interview discussion topics

See docs/INTERVIEW_GUIDE.md for the full Q&A: why Postgres is the sole
inventory authority, optimistic vs pessimistic choices per path, why not
SERIALIZABLE-everywhere, what happens under 1,000 concurrent requests,
scaling hot inventory (sharded pools / queue-fronted), and the consistency
boundaries table.

## Limitations (stated plainly)

See [LIMITATIONS.md](LIMITATIONS.md) for the complete, unvarnished list —
the summary version:

- JWT auth is HS256 (symmetric): right for a single service; a
  multi-service deployment would switch to RS256. No refresh tokens or
  revocation list — short TTL + per-request suspension reload cover the
  gaps. Login endpoint is not yet rate-limited.
- Webhook HMAC is enforced and tested, but assumes a single PSP
  relationship (one shared secret, no replay window).
- Kafka HA topology is verified live (3 brokers, RF=3, leader-kill chaos
  drill with zero message loss); Postgres/Redis remain single-instance.
- Benchmarks are single-machine; absolute latencies reflect the
  environment, not the architecture's ceiling.
- OpenTelemetry is wired but opt-in and pointed nowhere by default
  (correlation IDs remain the always-on baseline).
