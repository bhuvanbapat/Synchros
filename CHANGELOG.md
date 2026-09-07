# Changelog

All notable changes to FlashReserve. Format: Keep a Changelog; this
project does not use semantic versioning (portfolio project, see git log).

## [Unreleased] — second hardening pass (closing the documented-limitations tier)

### Added — every gap called out in LIMITATIONS.md §3–§12, closed and verified

- **Webhook replay window (versioned signatures)**: `X-Signature` now
  carries `t=<unix-seconds>,v1=<base64-HMAC-SHA256(secret, t "." raw-body)>`
  (Stripe-style). Verification recomputes over the presented timestamp +
  exact raw bytes and rejects deliveries older than the tolerance
  (`PAYMENT_WEBHOOK_REPLAY_TOLERANCE_SECONDS`, default 300s). A captured
  valid delivery replayed raw is 401'd; the timestamp is signed material,
  so it cannot be refreshed without the secret. Legacy bare-base64
  signatures are refused.
- **Login brute-force lockout** (`LoginAttemptLimiter`): 5 failed
  credentials per (email, ip) inside 15 minutes → 429 before the
  credential check; Redis-backed, fail-open by design; success clears
  the counter. IP honors `X-Forwarded-For` (first hop).
- **Admin account suspension** (`POST /api/admin/users/{id}/state`):
  the account_state control finally has a code path. Suspension binds
  on the next request (fresh principal reload), reactivation restores
  the same live token; self-lockout refused; audit-trailed.
- **Fail-closed secret policy** (`SecretPolicy`): all secrets ≥ 32 chars
  always; the repository's documented placeholder values are refused
  under prod-like Spring profiles (`SECRET_POLICY_ENFORCE_NON_DEV`,
  default true) — a forgotten JWT_SECRET/PAYMENT_WEBHOOK_SECRET override
  crashes at boot instead of running with a printed secret.
- **Durable consumer retry counters** (V6 `consumer_retry`): poison
  message attempt counts survive restarts — bounded retry is bounded
  globally, not per uptime; rows purged on success/dead-letter and by
  retention.
- **Outbox publish lease** (V7 `leased_until`): the claim query is
  `FOR UPDATE SKIP LOCKED` with the lease predicate **in SQL** (a batch
  of leased rows can never starve the poll); claimed rows are leased
  for 60s, a crashed publisher's lease expires; failed sends re-lease
  with **exponential backoff** (2^retry s, cap 60s).
- **Scheduled, alertable reconciliation** (`ReconciliationJob`): full
  sweep every 5 min; findings increment
  `flashreserve_reconciliation_findings`, any finding flips
  `flashreserve_reconciliation_consistent` to 0 — one Prometheus alert
  expression away from paging.
- **Event-state gate**: reservations refused for CANCELLED / SOLD_OUT /
  CONCLUDED events and pre-sales-window events with a clean
  `EVENT_NOT_RESERVABLE` 409 (the CHECK vocabulary is finally enforced
  by the engine).
- **Strict event-payload contract**: consumer projections require a
  present, numeric, positive `userId` — malformed payloads fail the
  handler and dead-letter instead of silently writing userId=0 rows.
- **Real-Kafka loop IT** (`KafkaLoopIT`, Testcontainers KRaft broker on a
  deterministic host port): outbox→broker→consumer→effect, duplicate
  delivery → exactly one processed marker, poison → dead-letter with
  durable counter cleared. The loop had zero automated regression
  protection before.
- **Real-Redis IT** (`RedisRateLimitCacheIT`): token-bucket burst
  boundary, 16-way concurrent atomicity of the Lua script, cache-aside
  TTL (~3s), and login-lockout semantics — previously argued, now
  asserted.
- **Tracing hardening**: explicit `OtlpHttpSpanExporter` bean created
  only when `OTEL_EXPORTER_OTLP_ENDPOINT` is non-blank (the switch is
  the endpoint — no separate flag to forget); OTLP *metrics* push
  disabled by default (it used to POST to localhost:4318 every 60s with
  no collector); opt-in JWT-protected span probe
  (`POST /api/dev/span-probe`, `SPAN_PROBE_ENABLED`) proves exporter
  wiring independently of HTTP observation; collector config updated to
  v0.160 (debug exporter + OTLP/HTTP receiver, both ports mapped).
- `AuthHardeningIT` (stale-replay unit + HTTP, legacy-signature refusal,
  malformed-transport 400), `AccountSuspensionIT` (5 tests),
  `EventStateGateIT` (5), `SecretPolicyTest` (5), `ConsumerRetryTest`,
  strict-payload regressions in `EventHandlersTest` (4).
- `tools/smoke.ps1` extended to a 15-step drill: stale-replay 401 (with
  fresh-signature control), login lockout 429 against live Redis,
  suspension round-trip on a live token, form-encoded 400 transport
  check, plus the original flow.
- Frontend: session persistence via `sessionStorage` (tab-lifetime
  token) + Sign out button + test.

### Fixed — second-pass audit findings
- **Outbox claim query lied about SKIP LOCKED** and filtered leases in
  Java *after* LIMIT — a batch of leased rows starved the poll into
  returning nothing while work existed. Lease predicate moved into the
  SQL claim; post-filter removed.
- **Two separate transactions in the failure path** left a window where
  a FAILED row sat unleased; state flip + backoff lease now commit in
  one short transaction.
- **Login response lied about token TTL** (`expiresIn` hardcoded 3600
  regardless of `flashreserve.jwt.ttl-seconds`) — k6 helpers cache
  tokens off `expiresIn`, so a shortened TTL would have broken
  long-running benchmarks mid-flight. Now the real configured value.
- **`SpanProbeController` was unreachable** (gated on a profile nothing
  activated — dead endpoint). Rewired to `SPAN_PROBE_ENABLED` property,
  documented, and exercised in the live tracing drill.
- **Runaway OTLP metrics pusher** silenced by default.
- k6 helpers: cached tokens now re-login 60s before reported expiry.

### Verified — second pass (live on this machine, 2026-09-07)
- Backend: **87/87 tests green** (Testcontainers: Postgres, KRaft Kafka,
  Redis).
- Frontend: 6/6 vitest green, production build + type-check clean.
- Live smoke drill (compose stack + `tools/smoke.ps1`): all 15 steps
  passed — including stale webhook replay 401 with fresh-signature
  control, 6th failed login 429 (live Redis), suspended user's live
  token 401 → reactivated same token 200, form-encoded transport 400,
  reconciliation consistent with 0 findings.
- Live tracing drill: app with `OTEL_EXPORTER_OTLP_ENDPOINT` +
  `SPAN_PROBE_ENABLED=true` against the shipped collector —
  `flashreserve.span-probe`, HTTP `authorize request`, and scheduler
  spans all received and printed by the collector.
- Outbox drained 838/838 events live post-fix; 0 FAILED/PENDING/DEAD.

### Fixed — first hardening-pass findings (retained from prior entry)

### Added — production-hardening pass (closing every documented gap)

- **JWT bearer authentication** (replaces HTTP Basic):
  - `JwtService`: JDK-only HS256 issuer/verifier (standard JWS compact
    serialization, no external library); subject = email; custom claims
    carry numeric DB user id + role so ownership checks are untouched.
    Constant-time signature comparison; 60s expiry leeway; hard startup
    failure when the secret is < 32 chars.
  - `JwtAuthFilter`: verifies the token, then re-loads a FRESH principal
    from the DB each request — account suspension and role changes take
    effect immediately, tokens alone can never vouch for standing.
  - `POST /api/auth/login` now returns `{accessToken, tokenType,
    expiresIn, user}`; all API calls present `Authorization: Bearer`.
  - Frontend `api.ts` is token-based (login exchanges credentials once);
    k6 helpers log in per VU and cache the token.
- **Webhook HMAC-SHA256** (was documented-only, now enforced):
  - `WebhookSigner`: HMAC-SHA256 over exact raw payload bytes, base64 in
    `X-Signature` (Stripe-style); constant-time verification.
  - `RawBodyCaptureFilter`: byte-preserving request wrapper scoped to
    `/api/payment-webhooks` so verification sees exactly the signed bytes.
  - `PaymentRelay` signs every simulated PSP delivery; the HTTP endpoint
    rejects unsigned/tampered calls with 401 `WEBHOOK_SIGNATURE_INVALID`
    before any parsing or state change.
- **OpenTelemetry tracing** (opt-in): micrometer-tracing bridge + OTLP
  exporter; dormant by default (the endpoint is the switch), correlation
  IDs remain the always-on baseline. `docker-compose.ha.yml --profile
  tracing` ships an OTel Collector (both 4317 gRPC and 4318 HTTP).
- **HA Kafka topology** (`docker-compose.ha.yml`): 3-broker KRaft cluster,
  RF=3, min.insync.replicas=2, `tools/ha-create-topics.sh` provisions the
  3 app topics, `topic-verifier` service reports replication state.
- `AuthHardeningIT`: token tamper/expiry/wrong-secret, HMAC round-trip/
  tamper, unsigned + tampered webhooks rejected over real HTTP (10 tests).
- `tools/smoke.ps1`: one-command live verification of JWT login, HMAC
  enforcement, full reserve→order→pay flow, notifications, admin metrics,
  reconciliation.

### Fixed — hardening-pass findings
- **Strict-firewall rejections surfaced as 500**: malformed/unsafe
  requests rejected by Spring's StrictHttpFirewall now map to structured
  400 `INVALID_REQUEST` instead of `INTERNAL_ERROR` (found live during
  the E2E smoke).

### Verified — hardening pass (live on this machine)
- Backend: **55/55 tests green** (45 + 10 new auth-hardening tests).
- Frontend: 5/5 vitest green, production build green, type-check clean.
- Live smoke (`tools/smoke.ps1`): JWT login → catalog → inventory →
  reserve → order → signed-webhook pay → CONFIRMED; unsigned + tampered
  webhook 401; tampered JWT 401; reconciliation consistent, 0 findings.
- HA chaos drill: killed the events-topic leader → failover to another
  broker, ISR 3→2, **10/10 messages survived, zero loss**; broker
  rejoined cleanly.

### Fixed — audit-driven correctness pass (all verified by new regression tests)
- **Concurrent duplicate idempotency-key requests returned HTTP 500**
  (`UnexpectedRollbackException`): a constraint violation during Hibernate
  flush marks the REQUIRES_NEW transaction rollback-only before the catch
  block runs. Claim is now `INSERT .. ON CONFLICT DO NOTHING` rowcount
  semantics. Regression: `ConcurrentIdempotencyClaimIT` (16 threads →
  1 Fresh, 15 InFlight, 0 exceptions).
- **Order-create vs expiration race**: `createOrderForReservation` now
  takes the reservation row lock (owner-scoped) before state/TTL checks —
  an orphaned PENDING_PAYMENT order on an EXPIRED reservation is no
  longer possible. Regression: `OrderCreateExpirationRaceIT` (20 rounds).
- **Payment TIMEOUT bricked retries**: a timed-out payment went terminal
  (`TIMED_OUT`), so every later callback 409'd forever. The payment now
  stays INITIATED and a late webhook / client retry confirms normally.
  Regression: `PaymentRetryIT`.
- **Kafka consumers silently ran without transactions**: listener
  self-invocation bypassed the `@Transactional` proxy. Split into
  `EventHandlers` (transactional marker+effect) + `DeadLetterService`
  (REQUIRES_NEW quarantine); unit tests added for dedup/quarantine.
- **Outbox publisher held a DB transaction across blocking Kafka sends**:
  under broker outage it would exhaust the connection pool and stall the
  API. Publisher now uses short read/mark transactions with all broker
  I/O outside any transaction (programmatic TransactionTemplate).
- **Admin `/api/admin/metrics` meltdown risk**: 6 full-table scans +
  one query per pool (N+1) replaced with grouped queries.
- **Health endpoint leaked a Redis connection** per check (borrowed
  connection never released).
- **Suspended accounts could authenticate**: `account_state` is now
  enforced in `loadUserByUsername`.
- **Register race**: concurrent duplicate-email registrations produced
  raw 500s; both paths now yield the clean 400 domain rejection.
- **Confirm/cancel of a raced reservation surfaced as 500**
  (`IllegalStateException`); mapped to 409 domain errors
  (RESERVATION_EXPIRED / INVALID_STATE_TRANSITION).
- **No CORS configuration** + absolute frontend API base broke the dev
  UI cross-origin; frontend now defaults to the vite proxy and the
  backend enforces an explicit origin allow-list.
- **Expiration scan fetched unbounded rows** then limited in memory;
  now query-level `Limit`.
- **Inventory endpoints exposed sequential DB ids**; all lookups are
  UUID-only now.
- Reservation failures now increment an error counter
  (`flashreserve_reservation_outcome{outcome=error}`);
  `flashreserve_expired_holds` wired to the expiration job.

### Removed — dead code
- `InventoryItem` entity + repository + `inventory_item` table (V5
  migration): never read or written by any code path.
- `SecurityConfig.RequestIdAuthBridge` no-op filter.
- Unused repository queries (PaymentRepository ×2, AuditRepository,
  AnalyticsRepository, OutboxRepository's misplaced idempotency purge +
  dead inner interface), `CatalogCache` catalog methods,
  `AuditService.recordNew`, `FlashReserveTopics.NOTIFICATION_EVENTS`,
  `InventoryPool.addAvailable`, dead `cancelIfHeld`/`confirmIfHeld`
  conditional updates, unused imports.

### Changed — load-test correctness
- `duplicate-request.js` used a per-VU `Date.now()` key — every VU got a
  *different* key (N logical reservations, the opposite of the benchmark's
  purpose). Keys are now run-scoped via env.
- `flash-sale.js` / `duplicate-request.js` declare expected statuses
  (201/409/429) so designed contention rejections are not counted as
  protocol failures.

### Added
- Docs: ARCHITECTURE, CONCURRENCY, DATABASE, EVENTS, IDEMPOTENCY,
  SECURITY, OBSERVABILITY, PERFORMANCE (measured), DEMO, RESUME_NOTES,
  INTERVIEW_GUIDE + ADR-001…010.
- GitHub Actions CI: backend build + tests (Testcontainers), frontend
  lint/test/build.
- BUILD_STATUS.md tracking; LICENSE (MIT).

### Verified
- Backend: **55/55 tests green** (unit + Testcontainers integration).
- Frontend: 5/5 vitest green, production build green, type-check clean.
- Live benchmarks (post-fix): 500 clients vs 100 units → exactly 100
  successes, 400 clean 409s, 0 5xx, reconciliation consistent; 20
  concurrent duplicate requests → exactly 1 reservation, 0 5xx.
