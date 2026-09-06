# FlashReserve — The Brutal, Honest Truth

Every real limitation of this project, ranked by how much it would hurt
in production. No marketing. Some of these are acceptable portfolio
tradeoffs; some are genuine gaps. All are known.

**Reading guide:** 🔴 would cause an incident in production.
🟡 would embarrass you in a senior interview if you didn't know it.
🟢 acceptable/deliberate tradeoff, but you should be able to defend it.

---

## 1. It has never run anywhere but one developer machine

🔴 **The single most important truth.** Every measurement in
docs/PERFORMANCE.md came from a desktop where the app, PostgreSQL, Kafka,
Redis, AND the load generator shared CPU. That means:

- The p95 of 22.4s under 500-VU contention is dominated by local resource
  starvation, not architecture. It tells you almost nothing about real
  behavior under proper separation.
- ~19 req/s throughput is a number about *that machine*, not this design.
- The steady-traffic run **missed its own p95 target** (1.39s vs <500ms).
  That's recorded honestly in PERFORMANCE.md, but it's still a miss.
- "Scales horizontally" is an *argument*, not a *measurement*. This app
  has never been run as two instances.

If someone asks "what did it do under load?" the honest answer is: "on a
contended dev box, the invariants held perfectly and the latencies were
meaningful only relative to that box."

## 2. Single points of failure everywhere

🔴 A production reservation platform needs HA. This has:

- **One PostgreSQL.** It dies → the platform dies. No replica, no
  failover, no PITR configured — just claims about how you *would*.
- **Kafka: the HA topology now exists and is verified** (3-broker KRaft
  cluster, RF=3, min ISR=2; chaos drill killed the events-topic leader
  and 10/10 messages survived — `docker-compose.ha.yml`). But the *app*
  still ships with the single-broker compose as the default, Postgres and
  Redis remain single-instance, and the HA cluster has never carried the
  full benchmark suite.
- **One app instance.** No load balancer, no rolling deploys, no
  health-gated restarts. Compose restarts the whole thing.
- **Redis** is the one component where failover doesn't matter by design
  (fail-open) — that part is genuinely defensible.

## 3. The auth would not survive contact with the internet

🟡 **JWT now, not Basic** — tokens are HS256-signed, tamper/expiry/
wrong-secret rejected, suspension enforced per-request via fresh
principal reload. What still would not survive:

- **No TLS in the demo** — bearer tokens must not cross untrusted hops
  in clear (terminate TLS at the LB; the app doesn't do it itself).
- **No lockout, no throttling on login** — the rate limiter still only
  guards `POST /api/reservations`. Brute-forcing `/api/auth/login` is
  unimpeded. An auth-attempt limiter is the next addition.
- Register has no CAPTCHA/email verification; anyone can create
  unlimited accounts (the load tests create 500 per run against the
  "production" DB).
- **No refresh-token rotation or revocation list** — tokens are 1h and
  the per-request reload covers suspension, but a leaked token is valid
  until expiry with no server-side kill switch.
- HS256 (symmetric): right for one service; multi-service wants RS256 so
  verifiers never hold the signing key.
- Passwords are bcrypt cost 10 — fine — but no rotation, no 2FA.

## 4. The payment webhook ~~is a bank vault with the door marked "please knock"~~ now checks signatures

🟢→🟡 The previous state (public unauthenticated state-mutating endpoint)
is closed: **every delivery must now carry HMAC-SHA256 over the exact
raw bytes; unsigned/tampered → 401 before parsing** (verified over real
HTTP in AuthHardeningIT; the relay signs every simulated payment so the
path runs constantly). Remaining honesty:

- A *correctly signed* forged SUCCESS on someone else's pending order
  still confirms it — HMAC proves origin, not authorization to apply.
  With a single shared secret, anyone holding the PSP secret can do
  this. Multi-PSP setups key secrets per provider and scopes are
  narrower.
- No timestamp/replay-window check in the signature scheme (a captured
  valid delivery could be replayed raw — idempotency absorbs the state
  effect, but the attempt row is still written).
- The secret is env-injected but there is no rotation story.

## 5. Consumer poison handling has a hole that resets

🟡 Kafka consumer retry counters are **in-memory** (`ConcurrentHashMap`).
Restart the app mid-poison-message and the attempt count resets to zero —
so a message that already failed 4 times gets 5 fresh attempts after every
restart. Bounded retry becomes "bounded per uptime." The dead-letter
*table* is durable; the *decision to dead-letter* is not. The production
answer (retry topics / `DeliveryAttemptHeader` with a state store) is
documented in ADR-005 as an upgrade, not built.

## 6. The outbox publisher has three known sharp edges

🟡 The restructure (no broker I/O inside transactions) fixed the worst
bug, but be honest about what remains:

- **Poll-based drain**: up to `OUTBOX_POLL_INTERVAL_MS` (500ms) latency
  on a good day, and up to 10 retry cycles (each ~500ms apart) of pure
  failure during a broker outage before a row even reaches FAILED.
- **No row locking between publisher instances.** Run two app replicas
  and both will fetch the same PENDING batch and publish duplicates.
  Harmless *only* because consumers dedup by eventId — the system is
  correct, but the publisher is not multi-instance-efficient (duplicate
  sends burn broker capacity). `SKIP LOCKED` would fix it; it's not there.
- **No exponential backoff** — a dead broker gets hammered every 500ms by
  every eligible row, for up to `maxRetries` rounds. After that the row
  is DEAD and needs manual intervention (by design — but "manual
  intervention" is a human runbook that doesn't exist in this repo).

## 7. The reconciliation is read-only

🟡 It *finds* `POOL_MISMATCH`, stuck holds, and orphaned orders. It
*fixes* none of them. There's no repair action, no alerting wiring, no
scheduled run (it's on-demand via the admin endpoint only). In production
this is step one of a control loop; here it's a smoke detector with no
sprinklers and no one watching the alarm.

## 8. No scheduler persistence or leadership election

🟡 The expiration job runs `@Scheduled` in every instance. One instance:
fine. Two instances: both scan, both race the same holds — and here's the
uncomfortable part — **the code is actually correct under that race**
(conditional flips make double-expiry impossible), but both instances
also call `expireIfPending` on orders and write duplicate log noise. So
multi-instance is *safe but uncoordinated*. No ShedLock, no Quartz, no
leader election. Fine for one node; a foot-gun for the "scale
horizontally" story the docs tell.

## 9. Things the tests don't cover (that they pretend they might)

🟡 Be precise about what 55 green tests does *not* mean:

- **No Kafka integration test in CI.** `flashreserve.kafka.enabled=false`
  in the whole IT suite. The consumers are unit-tested with mocks; the
  publisher's `publishOne` is only ever exercised live (compose demo) —
  meaning **the publisher→broker→consumer loop has zero automated
  regression protection.** The Kafka Testcontainers dependency exists in
  the pom and is never used. (The HA chaos drill verified the *cluster*,
  not the app's consumers against it.)
- **No schema-migration failure test** (Flyway runs before each IT via
  the container, which is decent — but no test pins a migration rollback
  or a checksum-conflict path).
- **The frontend tests mock the entire API layer** — 5 passing vitests
  prove the components render, not that the contract works. The contract
  is only proven by the manual E2E and the k6 runs.
- **No test runs the app as two instances** (see #8 — the claim "scales
  horizontally" is untested).
- **`RedisRateLimiter` and `CatalogCache` are never tested with a live
  Redis** — ITs exclude Redis entirely; their Lua script's atomicity is
  argued, not asserted. A typo in the Lua would ship green.

## 10. Data-model shortcuts taken on purpose (that you must own)

🟡

- **Flat price**: `PRICE_PER_UNIT_CENTS = 5000` hardcoded. No pricing
  table, no per-section pricing, no currency handling beyond a stored
  string.
- **Single-item reservations only**: the schema has no
  `reservation_item` child table; quantity is an int on the row. Multi-
  section carts are a redesign, not an extension.
- **No refunds, no exchanges, no transfers** — CONFIRMED and FAILED are
  hard terminals. A "refund" here means SQL surgery.
- **Events/pools have no admin CRUD** — inventory is seeded by
  migration and my ad-hoc SQL. The admin API is read/ops-only. There is
  literally no supported way to create an event through the API.
- **User deletion/GDPR erasure**: nonexistent; audit rows reference users
  forever (arguably correct for audit, but the policy is unwritten).

## 11. Observability stops at "you could look at it"

🟡 Micrometer counters exist and Prometheus can scrape them — but there
are **no dashboards, no alert rules, no SLOs** in the repo. The p95
miss in PERFORMANCE.md was discovered by reading k6 output, not by an
alert. Tracing: OpenTelemetry is now wired (opt-in, OTLP export +
collector profile in the HA compose) — a real deployment still needs to
point it at Jaeger/Tempo and actually read the spans; nobody has yet.

## 12. Smaller paper cuts, all real

- Idempotency: replay stores the **serialized response**, so a replay of
  a hold returns the original `holdExpiresAt` — a retry after 3 minutes
  "succeeds" with an already-expired hold. Semantically defensible
  (exactly-once means returning the original result), operationally
  surprising. No `X-Idempotent-Replay` warning is consumed by clients.
- The `V2` seed still contains the `inventory_item` INSERT that V5 later
  drops — harmless (migrations run in order) but a fresh DB builds and
  destroys rows for nothing; kept only to preserve frozen checksums.
- `Event` state (`SOLD_OUT`, `CONCLUDED`, `CANCELLED`) exists in the
  CHECK constraint but **no code ever sets it** — reservation logic
  ignores event state entirely. You can reserve a CANCELLED event today.
- `account_state` is enforced at login... but there is no endpoint or
  path to ever set a user to SUSPENDED. The control exists; the feature
  to use it doesn't.
- Consumers project `userId` from the event payload with `asLong()` — a
  malformed payload yields userId=0 notifications silently. Dedup
  prevents chaos; data-quality validation of payloads is thin.
- The whole demo leans on **seeded users with password `password`** and
  BCrypt hashes committed in a migration. Fine for a demo; a habit that
  must die before anything real.
- Tests reach into entities with **reflection** (`seedPool()` helpers
  set private fields). It works; it will also silently break on any
  entity rename — a brittle-test smell.
- `flash-sale.js` registration step means "1000 reqs" includes 500 signups
  — the benchmark conflates setup cost with the measured path unless you
  read the counters carefully (business counters are separate, which is
  the saving grace).

## 13. What is NOT a limitation (so nobody "fixes" a non-problem)

🟢 These get challenged in reviews and are actually sound:

- **Redis not owning inventory** — that's the design being correct, not a
  gap. Making Redis authoritative would be the bug.
- **Modular monolith instead of microservices** — the module boundaries
  are the point; splitting would *reduce* correctness guarantees here.
- **Poll-based outbox vs. CDC (Debezium)** — a legitimate tradeoff at this
  scale; CDC adds an operational surface this project has no story for.
- **The conditional-UPDATE CAS vs. an exotic lock manager** — the
  mechanism is the boring right answer; the 500-VU run is the evidence.
- **Kafka at-least-once + consumer dedup** — exactly-once *effects* is
  the correct target; transactions across Kafka are a known trap.

---

## The honest summary

> This is a **correctness-first, single-node proof of concept with
> production-grade patterns and demo-grade infrastructure.** The
> invariants (no oversell, no duplicate effects, no lost events) are
> genuinely proven by tests and live drills. The 2026-09 hardening pass
> closed the four loudest gaps — JWT auth, webhook HMAC, opt-in
> OpenTelemetry, verified Kafka HA topology — but the operational story
> that remains (Postgres/Redis HA, multi-instance coordination, login
> throttling, alerting, migration tooling) is still future work, and every
> one of those gaps would be found in a real production review.

If this project is presented as "a serious backend engineering project
that could be discussed in an interview" — it is exactly that.
If it's presented as "production-ready" — it is not, and the reason is
written above.
