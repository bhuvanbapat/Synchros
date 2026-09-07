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
- **Login lockout exists now** — `LoginAttemptLimiter` locks an (email,
  ip) window after 5 failures for 15 minutes (Redis-backed, fail-open),
  verified live in the smoke drill (6th attempt → 429) and against a
  real Redis in `RedisRateLimitCacheIT`. Honest remainder: the app
  trusts `X-Forwarded-For` as-is — correct behind the compose proxy,
  but on the public internet the LB must sanitize it or a spoofing
  client mints a fresh IP-scoped counter per request.
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
- ~~No timestamp/replay-window check~~ **Closed by the versioned
  signature scheme**: every delivery now carries `t=<unix-seconds>,
  v1=<HMAC(secret, t + "." + raw-body)>`; verification recomputes over
  the presented timestamp + exact bytes and rejects anything outside
  the tolerance window (default 300s, `PAYMENT_WEBHOOK_REPLAY_TOLERANCE_SECONDS`).
  A captured valid delivery replayed raw an hour later is 401'd —
  verified live in the smoke drill and pinned in `AuthHardeningIT`
  (stale-timestamp unit + HTTP tests). The timestamp is signed material,
  so it can't be refreshed without the secret.
- The secret is env-injected but there is no rotation story.

## 5. Consumer poison handling ~~has a hole that resets~~ is durable now

🟢→🟡 Kafka consumer retry counters live in the **`consumer_retry` table
(V6)**, not process memory. A restart no longer resets the poison
clock — "bounded retry" is bounded globally, not per uptime. The
end-to-end loop (outbox → broker → consumer → effect, duplicate
delivery → exactly one effect, poison → dead-letter with the durable
counter cleared) is proven against a **real KRaft broker via
Testcontainers in `KafkaLoopIT`** — CI-automated, no manual steps.
Honest remainder: no delay/backoff between consumer attempts (a hot
poison message still retries 5 times back-to-back before quarantine),
and retry topics remain documented-not-built (ADR-005).

## 6. The outbox publisher ~~has three known sharp edges~~ got the three fixes

🟢→🟡 The restructure (no broker I/O inside transactions) fixed the worst
bug; this pass closed the other three:

- **Poll-based drain remains** (500ms worst-case latency on a good
  day) — the accepted tradeoff of poll over CDC.
- **Row claiming is now multi-instance-safe**: the claim query is
  `SELECT ... FOR UPDATE SKIP LOCKED` with an explicit **publish lease**
  (`leased_until`, V7) set inside the claim transaction; a second
  instance cannot re-send rows the first is actively publishing, and a
  crashed publisher's lease simply expires (60s) and the row returns
  to the pool. The lease predicate lives in the claim SQL, so a batch
  of leased rows can never starve the poll. Consumer dedup remains the
  final correctness guarantee regardless.
- **Exponential backoff is in**: a failed send re-leases the row for
  2^retry seconds (capped 60s) — a dying broker gets polled less, not
  hammered at 500ms. Rows still reach DEAD after `maxRetries` and need
  the (still manual) runbook — that part is unchanged and honest.

## 7. The reconciliation ~~is read-only~~ is scheduled and alertable now

🟢→🟡 `ReconciliationJob` runs the full sweep every 5 minutes
(`flashreserve.reconciliation.interval-ms`), increments
`flashreserve_reconciliation_findings` and flips the
`flashreserve_reconciliation_consistent` gauge to 0 on any finding —
alertable from Prometheus with `consistent == 0`. Still read-only
(finds POOL_MISMATCH, stuck holds, orphaned orders; fixes none) and
the repo still ships no dashboards or alert *rules* — but the smoke
detector now has a bell wired to it, and nobody has to press the button.

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

🟡 Be precise about what 87 green tests does *not* mean:

- ~~**No Kafka integration test in CI.**~~ **Closed by `KafkaLoopIT`:**
  a real KRaft broker (Testcontainers, fixed host port 19092 to keep
  advertised-listener semantics deterministic) runs the full
  publisher→broker→consumer loop: outbox row flips PUBLISHED, the
  notification projection appears, duplicate delivery produces exactly
  one processed marker, and a poison message dead-letters with the
  durable counter cleared. Consumers are no longer mock-only.
- **No schema-migration failure test** (Flyway runs before each IT via
  the container, which is decent — but no test pins a migration rollback
  or a checksum-conflict path).
- **The frontend tests mock the entire API layer** — 6 passing vitests
  prove the components render (including session persistence/sign-out),
  not that the contract works. The contract is only proven by the manual
  E2E and the k6 runs.
- **No test runs the app as two instances** (see #8 — the claim "scales
  horizontally" is untested; the outbox lease makes it *safe*, but no
  test demonstrates two replicas side by side).
- ~~**`RedisRateLimiter` and `CatalogCache` are never tested with a
  live Redis**~~ **Closed by `RedisRateLimitCacheIT`:** real Redis
  container pins the token bucket (burst 3 exactly, concurrent 16-way
  atomicity), the cache-aside TTL contract (~3s), and the login-lockout
  counter semantics (5-failure lock, IP-scoped independence, success
  clears).

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

## 11. Observability ~~stops at "you could look at it"~~

🟢→🟡 Micrometer counters exist, Prometheus can scrape them, the
scheduled reconciliation run now exports `flashreserve_reconciliation_findings`
/ `_consistent` so an *alert rule* is one Prometheus expression away.
Tracing is wired **and verified live end-to-end**: the opt-in OTLP/HTTP
exporter + span probe delivered spans (HTTP `authorize request`, the
outbox scheduler task, and `flashreserve.span-probe`) into the shipped
collector during the verification drill. Still honest: the repo ships
**no dashboards, no alert rules, no SLOs** — the gauges exist; the
dashboards are left as an exercise for the operator, and the collector
runs the debug exporter (stdout), not a real backend like Jaeger/Tempo.

## 12. Smaller paper cuts, mostly closed this pass

- Idempotency: replay stores the **serialized response**, so a replay of
  a hold returns the original `holdExpiresAt` — a retry after 3 minutes
  "succeeds" with an already-expired hold. Semantically defensible
  (exactly-once means returning the original result), operationally
  surprising. No `X-Idempotent-Replay` warning is consumed by clients.
- The `V2` seed still contains the `inventory_item` INSERT that V5 later
  drops — harmless (migrations run in order) but a fresh DB builds and
  destroys rows for nothing; kept only to preserve frozen checksums.
- ~~`Event` state exists in the CHECK constraint but reservation logic
  ignores it~~ **Closed by the event-state gate**: `create()` refuses
  CANCELLED / SOLD_OUT / CONCLUDED events and pre-sales-window events
  with a clean `EVENT_NOT_RESERVABLE` 409; pinned by `EventStateGateIT`
  (including the not-regressed happy path). No code *sets* those states
  via API yet (admin event CRUD remains nonexistent) — the vocabulary is
  DB-seeded — but the engine no longer ignores it.
- ~~`account_state` enforced but nothing can set SUSPENDED~~ **Closed by
  `POST /api/admin/users/{id}/state`** (`AccountAdminService` + audit
  trail + self-lockout refusal). `AccountSuspensionIT` pins the whole
  loop: live token 401s on the next request, reactivation restores the
  same token.
- ~~Consumers project `userId` with `asLong()` defaults~~ **Closed by
  the strict payload contract** (`requiredPositiveLong`): a missing/
  non-numeric/zero userId fails the handler → retries → dead-letters
  like any poison message, instead of silently projecting userId=0
  notification rows. Regression-tested in `EventHandlersTest`.
- The whole demo leans on **seeded users with password `password`** and
  BCrypt hashes committed in a migration. Fine for a demo; a habit that
  must die before anything real. (The fail-closed `SecretPolicy` at
  least refuses the committed *secrets* under prod-like profiles.)
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
> genuinely proven by tests and live drills — 87 backend tests now
> including a real-broker Kafka loop IT and a real-Redis IT, plus a
> live smoke drill covering replay-window rejection, login lockout,
> and account suspension. The 2026-09 hardening passes closed the four
> loudest gaps — JWT auth, webhook HMAC with a replay window, verified
> end-to-end OpenTelemetry, verified Kafka HA topology — and the
> second pass closed the next tier: durable consumer retries, the
> SKIP LOCKED + lease outbox claim with exponential backoff, scheduled
> alertable reconciliation, the event-state gate, admin account
> suspension, strict event-payload contracts, fail-closed secret
> policy, and login brute-force lockout. The operational story that
> remains (Postgres/Redis HA, multi-instance *deployment*, dashboards
> and alert rules, migration tooling, TLS) is still future work, and
> every one of those gaps would be found in a real production review.

If this project is presented as "a serious backend engineering project
that could be discussed in an interview" — it is exactly that.
If it's presented as "production-ready" — it is not, and the reason is
written above.
