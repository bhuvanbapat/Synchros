# BUILD STATUS

Living checklist — updated as work completes. Last full verification:
2026-09-07 (all measurements taken live on this machine, never invented).

## Infrastructure

- [x] Environment inspected (Java 25, Maven wrapper, Docker 29, k6 v1.4.0, git, Node 24)
- [x] Project isolated at `Desktop\AI Project 3\FlashReserve` (touches nothing else)
- [x] Docker Compose: postgres / redis / kafka / app — no duplicated or decorative services
- [x] `.env.example` with safe local defaults; `.env` gitignored
- [x] Graphify available (v0.9.48) for dev-time structural analysis

## Backend

- [x] Domain model + explicit state machines (reservation, order) with guarded transitions
- [x] Database schema V1–V7 (Flyway, `ddl-auto: validate`), CHECK-constrained invariants
- [x] Reservation engine: hold / confirm / cancel / expire (+ event-state & sales-window gate)
- [x] Concurrency control: atomic conditional decrement (CAS) + row locks + @Version backstop
- [x] Idempotency: (user, operation, key) claims via ON CONFLICT DO NOTHING, fingerprints, cached replays
- [x] Orders + deterministic mock gateway (success/failure/timeout weights)
- [x] Payment webhook idempotency: duplicate + late callbacks absorbed
- [x] Transactional outbox: PENDING → PUBLISHED / FAILED → DEAD; broker I/O outside all txs;
      claim via FOR UPDATE SKIP LOCKED + 60s publish lease (V7) with exponential backoff
- [x] Kafka: 3 topics, producer idempotence, consumer dedup via processed_event,
      DURABLE retry counters (V6 consumer_retry), dead-letter quarantine
- [x] Redis: availability hint cache (3s TTL, fail-open) + Lua token-bucket rate limiting
      + login brute-force lockout (5 fails / 15 min per (email, ip), fail-open)
- [x] Audit trail (append-only, requestId captured) + request correlation end-to-end
- [x] Observability: Actuator, Micrometer business counters + reconciliation gauges
      (flashreserve_reconciliation_findings / _consistent), structured logs
- [x] Reconciliation: pool invariants, stuck holds, orphaned orders (REPEATABLE_READ snapshot);
      scheduled every 5 min + on-demand admin endpoint; findings → metrics
- [x] Security: **JWT bearer auth** (HS256, JDK-only issuer/verifier, constant-time compare,
      tamper/expiry/wrong-secret rejected), role checks, owner-scoped queries (anti-IDOR),
      CORS allow-list, admin account suspension (POST /api/admin/users/{id}/state, audit-trailed,
      self-lockout refused, binds on next request via principal reload), validation,
      injection-safe queries, **fail-closed SecretPolicy** (≥32 chars always; repo placeholders
      refused under prod-like profiles)
- [x] **Webhook HMAC-SHA256, versioned + replay-windowed**: X-Signature carries
      t=<unix-s>,v1=<HMAC(secret, t "." raw-body)>; constant-time compare over exact raw bytes
      (byte-preserving capture filter); stale deliveries (default >300s) → 401 before any
      state change; legacy bare-signature format refused
- [x] Strict-firewall + malformed-transport rejections map to structured 400 (never 500)
- [x] Consumer payload contract: userId must be present/numeric/positive — bad payloads
      fail → retry → dead-letter (no silent userId=0 projections)
- [x] OpenTelemetry: opt-in OTLP/HTTP span exporter (endpoint = the switch), OTLP metrics
      push disabled by default, JWT-protected span probe (SPAN_PROBE_ENABLED)

## Concurrency validation (the centerpiece)

- [x] OversellPreventionIT: 100 clients vs 10 units → ≤10 succeed, available ≥ 0 (repeated 3×)
- [x] Live k6 flash-sale: **500 clients vs 100 units → exactly 100 successes, 400 clean 409s, 0 5xx, final available = 0**
- [x] Reconciliation after every benchmark: consistent, 0 findings
- [x] Confirm-vs-expire race: only legal outcomes (ExpirationIT + OrderCreateExpirationRaceIT, 20 rounds)
- [x] Order-create-vs-expiry race: 0 orphaned orders after fix (regression test)
- [x] Concurrent idempotency claims: 16 threads → 1 Fresh, 15 InFlight, 0 exceptions (regression test)
- [x] 8-way concurrent duplicate payment callbacks → exactly 1 effect
- [x] AuthHardeningIT: token tamper/expiry/wrong-secret + versioned-HMAC round-trip/tamper +
      stale-replay + legacy-signature + unsigned/tampered webhooks 401 + form-transport 400 over real HTTP
- [x] AccountSuspensionIT: suspend → live token 401s next request → reactivate → same token 200;
      non-admin 403, self-lockout 400, garbage state 400, unknown user 404
- [x] EventStateGateIT: CANCELLED/SOLD_OUT/CONCLUDED/pre-sales-window → EVENT_NOT_RESERVABLE;
      happy path not regressed

## Tests

- [x] Unit: state machines, fingerprints, consumer dedup, quarantine, durable retry counters,
      SecretPolicy, strict payload contracts
- [x] Integration (Testcontainers, real Postgres): 10 IT classes
- [x] **KafkaLoopIT (real KRaft broker)**: outbox→broker→consumer→effect; duplicate delivery →
      1 processed marker; poison → dead-letter + counter cleared
- [x] **RedisRateLimitCacheIT (real Redis)**: token-bucket burst + 16-way atomicity,
      cache-aside TTL, login-lockout counting/scoping/clearing
- [x] API-level security: JWT auth, IDOR, admin protection, injection, validation
- [x] Regression tests pin every fixed bug (incl. the SKIP LOCKED/lease starvation fix)
- [x] **Backend: 87/87 green**
- [x] Frontend: 6/6 vitest green (incl. session persistence + sign-out); production build green; type-check clean

## Frontend

- [x] Catalog → inventory → reserve → server-authoritative hold countdown → order → simulated pay → confirmation
- [x] Session persistence via sessionStorage (token dies with the tab) + Sign out
- [x] Admin ops dashboard: pools (total/available/held/sold), reservations by state, outbox by state, dead letters, reconciliation, live refresh

## Load tests (measured — docs/PERFORMANCE.md)

- [x] flash-sale.js — contention benchmark (500 VUs)
- [x] duplicate-request.js — idempotency benchmark (20 VUs, 1 key → 1 reservation)
- [x] steady-traffic.js — mixed browse/reserve profile
- [x] k6 helpers: token cache keyed to server-reported expiresIn (refresh 60s early)
- [x] Two real bugs + two hotspots found and fixed by these benchmarks (idempotency claim 500s; outbox tx-across-IO; admin N+1; unbounded expiry scan)

## HA topology (measured live — docker-compose.ha.yml)

- [x] 3-broker KRaft cluster (RF=3, min.insync.replicas=2, controllers co-located)
- [x] All 3 app topics created with RF=3; ISR full on every partition
- [x] **Chaos drill: killed the events-topic leader → leadership failed over to another broker, ISR shrank 3→2, 10/10 messages survived with zero loss; broker rejoined cleanly after restart**

## Tracing (verified live end-to-end)

- [x] OpenTelemetry wired (micrometer-tracing bridge + explicit OTLP/HTTP span exporter);
      the endpoint is the on/off switch — no endpoint, no bean, zero overhead
- [x] OTLP metrics push disabled by default (was noise-POSTing localhost:4318 every 60s)
- [x] JWT-protected span probe (POST /api/dev/span-probe, SPAN_PROBE_ENABLED) proves
      exporter wiring independently of HTTP observation
- [x] **Live drill: flashreserve.span-probe + HTTP authorize-request + outbox scheduler
      spans received and printed by the shipped collector (v0.160, OTLP/HTTP + gRPC)**

## Live smoke drill (tools/smoke.ps1 — 15 steps, all passing 2026-09-07)

- [x] JWT login → catalog → inventory → reserve (idempotent) → order → signed-webhook pay → CONFIRMED
- [x] Unsigned + tampered webhooks 401; tampered JWT 401
- [x] **Stale-replay webhook 401** (timestamp dragged 1h past; fresh-signature control passes HMAC)
- [x] **Login lockout: 6th failed attempt → 429 against live Redis** (fresh registered user)
- [x] **Suspension round-trip: bob suspended → live token 401s → reactivated → same token 200**
- [x] Form-encoded transport to JSON API → clean 400
- [x] Notifications (Kafka projection) + admin metrics + reconciliation consistent, 0 findings

## Docs & packaging

- [x] README, CHANGELOG, CONTRIBUTING, LICENSE (MIT)
- [x] docs/: ARCHITECTURE, CONCURRENCY, DATABASE, EVENTS, IDEMPOTENCY, SECURITY, OBSERVABILITY, PERFORMANCE, DEMO, RESUME_NOTES, INTERVIEW_GUIDE, LIMITATIONS
- [x] docs/adr/: ADR-001 … ADR-013
- [x] CI: GitHub Actions (backend build+tests, frontend lint+test+build)

## Known limitations (documented, deliberate)

- JWT is HS256 (symmetric) — right for a single service; multi-service deployments would switch to RS256 (see SECURITY.md)
- Webhook HMAC shared-secret model (single PSP relationship); no rotation story; login lockout trusts X-Forwarded-For (LB must sanitize on the public internet)
- HA compose demonstrates Kafka topology; Postgres/Redis remain single-instance (their HA is managed-service territory — documented in ADRs)
- Reconciliation is scheduled + alertable but read-only (no auto-repair)
- Benchmarks single-machine; latencies are local measurements, invariants are the portable result
