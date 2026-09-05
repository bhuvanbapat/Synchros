# BUILD STATUS

Living checklist — updated as work completes. Last full verification:
2026-09-05 (all measurements taken live on this machine, never invented).

## Infrastructure

- [x] Environment inspected (Java 25, Maven wrapper, Docker 29, k6 v1.4.0, git, Node 24)
- [x] Project isolated at `Desktop\AI Project 3\FlashReserve` (touches nothing else)
- [x] Docker Compose: postgres / redis / kafka / app — no duplicated or decorative services
- [x] `.env.example` with safe local defaults; `.env` gitignored
- [x] Graphify available (v0.9.48) for dev-time structural analysis

## Backend

- [x] Domain model + explicit state machines (reservation, order) with guarded transitions
- [x] Database schema V1–V5 (Flyway, `ddl-auto: validate`), CHECK-constrained invariants
- [x] Reservation engine: hold / confirm / cancel / expire
- [x] Concurrency control: atomic conditional decrement (CAS) + row locks + @Version backstop
- [x] Idempotency: (user, operation, key) claims via ON CONFLICT DO NOTHING, fingerprints, cached replays
- [x] Orders + deterministic mock gateway (success/failure/timeout weights)
- [x] Payment webhook idempotency: duplicate + late callbacks absorbed
- [x] Transactional outbox: PENDING → PUBLISHED / FAILED → DEAD, broker I/O outside all txs
- [x] Kafka: 3 topics, producer idempotence, consumer dedup via processed_event, dead-letter quarantine
- [x] Redis: availability hint cache (3s TTL, fail-open) + Lua token-bucket rate limiting
- [x] Audit trail (append-only, requestId captured) + request correlation end-to-end
- [x] Observability: Actuator, Micrometer business counters, structured logs
- [x] Reconciliation: pool invariants, stuck holds, orphaned orders (REPEATABLE_READ snapshot)
- [x] Security: Basic auth, role checks, owner-scoped queries (anti-IDOR), CORS allow-list, account suspension, validation, injection-safe queries

## Concurrency validation (the centerpiece)

- [x] OversellPreventionIT: 100 clients vs 10 units → ≤10 succeed, available ≥ 0 (repeated 3×)
- [x] Live k6 flash-sale: **500 clients vs 100 units → exactly 100 successes, 400 clean 409s, 0 5xx, final available = 0**
- [x] Reconciliation after every benchmark: consistent, 0 findings
- [x] Confirm-vs-expire race: only legal outcomes (ExpirationIT + OrderCreateExpirationRaceIT, 20 rounds)
- [x] Order-create-vs-expiry race: 0 orphaned orders after fix (regression test)
- [x] Concurrent idempotency claims: 16 threads → 1 Fresh, 15 InFlight, 0 exceptions (regression test)
- [x] 8-way concurrent duplicate payment callbacks → exactly 1 effect

## Tests

- [x] Unit: state machines, fingerprints, consumer dedup, quarantine
- [x] Integration (Testcontainers, real Postgres): 9 IT classes
- [x] API-level security: auth, IDOR, admin protection, injection, validation
- [x] Regression tests pin every fixed bug
- [x] **Backend: 45/45 green**
- [x] Frontend: 5/5 vitest green; production build green; oxlint clean

## Frontend

- [x] Catalog → inventory → reserve → server-authoritative hold countdown → order → simulated pay → confirmation
- [x] Admin ops dashboard: pools (total/available/held/sold), reservations by state, outbox by state, dead letters, reconciliation, live refresh

## Load tests (measured — docs/PERFORMANCE.md)

- [x] flash-sale.js — contention benchmark (500 VUs)
- [x] duplicate-request.js — idempotency benchmark (20 VUs, 1 key → 1 reservation)
- [x] steady-traffic.js — mixed browse/reserve profile
- [x] Two real bugs + two hotspots found and fixed by these benchmarks (idempotency claim 500s; outbox tx-across-IO; admin N+1; unbounded expiry scan)

## Docs & packaging

- [x] README, CHANGELOG, CONTRIBUTING, LICENSE (MIT)
- [x] docs/: ARCHITECTURE, CONCURRENCY, DATABASE, EVENTS, IDEMPOTENCY, SECURITY, OBSERVABILITY, PERFORMANCE, DEMO, RESUME_NOTES, INTERVIEW_GUIDE
- [x] docs/adr/: ADR-001 … ADR-010
- [x] CI: GitHub Actions (backend build+tests, frontend lint+test+build)

## Known limitations (documented, deliberate)

- HTTP Basic + TLS-external (portfolio scope; ownership logic is auth-agnostic)
- Webhook HMAC verification documented, not simulated (no real PSP)
- Single Kafka broker / single Postgres instance (HA topology documented in ADRs)
- Benchmarks single-machine; latencies are local measurements, invariants are the portable result
- OpenTelemetry spans not wired; request/event correlation IDs used instead
