# Resume Notes

## One-paragraph description (for portfolios)

Synchros is a high-concurrency reservation platform (limited-release
ticketing domain) engineered around one question: *how do you let 500
users fight over 100 units without overselling, double-charging, or losing
an event?* Inventory ownership is strictly transactional in PostgreSQL
(atomic conditional UPDATE + CHECK constraint backstop), every mutation is
idempotent (DB-claimed idempotency keys, Stripe-style timestamped
HMAC-verified payment webhooks with a replay window, consumer-side event
dedup with durable retry counters), and all business events flow through a
transactional outbox to Kafka with multi-instance-safe claiming
(FOR UPDATE SKIP LOCKED + publish leases with exponential backoff) and a
dead-letter state. Auth is stateless JWT (HS256, JDK-only, fresh-principal
reload so suspensions bind immediately, Redis-backed brute-force lockout).
Correctness is proven by 87 automated tests — including a 100-clients-vs-10-
units oversell test, a 16-thread concurrent idempotency-claim test, a
real-broker Kafka loop IT, and a real-Redis rate-limit/lockout IT — plus
measured k6 benchmarks (500 VUs → exactly 100 successes, 0 errors), a live
Kafka leader-kill chaos drill (RF=3, zero message loss), and a 15-step
live smoke drill covering replay-window rejection, login lockout, and
account suspension.

## Stack (actual)

Java 25 · Spring Boot 4.1 (Web, Data JPA, Security, Validation, Actuator) ·
PostgreSQL 17 + Flyway (V1–V7) · Redis (Lettuce, Lua token bucket, login
lockout) · Apache Kafka (KRaft, Spring Kafka, durable retry counters) ·
Micrometer/Prometheus + OpenTelemetry (opt-in OTLP/HTTP, verified live) ·
React 19 + TypeScript + Vite + vitest · Testcontainers (Postgres, Kafka,
Redis) · k6 · Docker Compose · GitHub-ready CI.

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

Second mention: the outbox publisher's claim query *documented*
`FOR UPDATE SKIP LOCKED` but the SQL never had it, and lease filtering
happened in Java *after* the LIMIT — so a batch full of leased rows made
the poll return nothing while work was queued (a starvation bug invisible
until you reason about the second instance). Fixed by moving the lease
predicate into the claim SQL; multi-instance safety is now claim-atomic.

## Mechanisms in one line each

- **Overselling**: `UPDATE inventory_pool SET available = available - :q
  WHERE id = :id AND available >= :q` — single-statement atomic CAS; the
  CHECK constraint makes negative inventory unrepresentable.
- **Idempotency**: (user, operation, key) UNIQUE row claimed via
  ON CONFLICT DO NOTHING; responses cached for replay; fingerprints (SHA-256)
  reject key reuse with different bodies.
- **Outbox**: event row committed in the business transaction; publisher
  claims batches via `FOR UPDATE SKIP LOCKED` + 60s publish lease (V7),
  sends with all broker I/O outside transactions, exponential backoff on
  failure, bounded retries → DEAD state; consumers dedup by
  (event_id, consumer_group).
- **Webhook authenticity**: `t=<ts>,v1=HMAC-SHA256(secret, ts "." raw-body)`
  — constant-time compare over exact bytes; replay window (default 300s)
  rejects captured deliveries; the timestamp is signed material.
- **Brute force**: 5 failed logins per (email, ip) → 15-minute lockout,
  Redis fixed-window counter, fail-open when Redis is down.
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
- **Live smoke drill (15 steps)**: JWT login → full reserve→order→
  signed-webhook pay flow; unsigned + tampered webhooks and tampered
  JWTs all 401; **stale-replay webhook 401** (fresh-signature control
  passes HMAC); **6th failed login 429** (live Redis); **suspended
  user's live token 401s → reactivated same token 200**; form-encoded
  transport clean 400; reconciliation consistent, 0 findings
  (tools/smoke.ps1).
- **Live tracing drill**: opt-in OTLP/HTTP export verified end-to-end —
  `Synchros.span-probe`, HTTP `authorize request`, and outbox
  scheduler spans all received by the shipped collector.
- **87 backend tests** (incl. real-broker KafkaLoopIT + real-Redis
  RedisRateLimitCacheIT) + **6 frontend tests**, all green.

## Resume bullets (ready to paste)

- Built a high-concurrency reservation platform (Java/Spring Boot,
  PostgreSQL, Kafka, Redis) where inventory overselling is impossible by
  construction: atomic conditional updates with DB CHECK-constraint
  backstops, proven by automated 100-vs-10 oversell tests and 500-VU k6
  benchmarks with zero oversells and zero 5xx responses.
- Implemented exactly-once-effect semantics across the request path —
  DB-claimed idempotency keys with request fingerprinting and cached
  replays, Stripe-style timestamped HMAC-SHA256 payment webhooks
  (byte-exact raw-body verification, constant-time compare, replay
  window), and Kafka consumer dedup with durable retry counters —
  and diagnosed/fixed a subtle transaction rollback-only bug in the
  concurrent idempotency claim (Hibernate flush violation → commit-time
  UnexpectedRollbackException) via load testing.
- Designed a transactional outbox with multi-instance-safe claiming
  (`FOR UPDATE SKIP LOCKED` + publish leases with exponential backoff,
  all broker I/O outside database transactions, bounded retries →
  dead-letter), so a Kafka outage never blocks business traffic and a
  second replica never double-sends; verified with live failure drills
  and a 3-broker leader-kill chaos run (RF=3, min.insync.replicas=2,
  zero message loss) plus a Testcontainers-based broker-loop regression
  test.
- Hardened the security surface end-to-end: stateless JWT auth (HS256,
  JDK-only, per-request principal reload so suspensions bind instantly),
  admin account suspension with audit trail, Redis-backed login
  brute-force lockout, fail-closed secret policy (prod-like boots crash
  on placeholder secrets), and a 15-step live smoke drill asserting every
  rejection path (tampered tokens, forged/stale webhooks, lockout,
  suspension) against the running stack.

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
  gaps. Login lockout is IP-scoped and trusts X-Forwarded-For behind the
  proxy (LB must sanitize it on the public internet).
- Webhook HMAC is enforced, timestamp-bound, and replay-windowed, but
  assumes a single PSP relationship (one shared secret, no rotation
  story).
- Kafka HA topology is verified live (3 brokers, RF=3, leader-kill chaos
  drill with zero message loss); Postgres/Redis remain single-instance.
  The outbox is multi-instance-*safe* (lease + SKIP LOCKED) but no test
  runs two app replicas side by side.
- Benchmarks are single-machine; absolute latencies reflect the
  environment, not the architecture's ceiling.
- OpenTelemetry is wired and verified live against the shipped collector
  (debug exporter); real deployments point it at Jaeger/Tempo. No
  dashboards or alert rules ship in the repo — the reconciliation gauges
  are one Prometheus expression away from paging.
- Reconciliation is scheduled and alertable but still read-only: it
  finds drift, it doesn't repair it.
