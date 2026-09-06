# Changelog

All notable changes to FlashReserve. Format: Keep a Changelog; this
project does not use semantic versioning (portfolio project, see git log).

## [Unreleased]

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
  exporter behind `OTEL_TRACING_ENABLED` + `OTEL_EXPORTER_OTLP_ENDPOINT`;
  dormant by default, correlation IDs remain the always-on baseline.
  `docker-compose.ha.yml --profile tracing` ships an OTel Collector.
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
