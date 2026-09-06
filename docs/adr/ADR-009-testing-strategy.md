# ADR-009: Testing strategy

**Status:** Accepted

## Context

The system's value is behavior under concurrency and failure. Sequential
happy-path tests would hide exactly the bugs that matter (one did).

## Decision

Layers, each with a distinct job:

| Layer | Scope | Examples |
|---|---|---|
| Unit | pure logic, no Spring | state-machine guards, fingerprints, consumer dedup with mocked repos |
| Integration (Testcontainers) | real Postgres, real transactions | OversellPreventionIT (100 vs 10), OrderCreateExpirationRaceIT (create/expire race, 20 rounds), PaymentFlowIT (8 concurrent duplicate callbacks → 1 effect), ExpirationIT, IdempotencyIT, ReconciliationIT (corruption detection), OutboxIT (commit/rollback atomicity) |
| API-level (RANDOM_PORT) | HTTP + security + idempotency end-to-end | ApiSecurityIT (JWT 401/403 gates, token tamper/expiry, IDOR, admin protection, injection), ApiIdempotencyIT (replay/conflict over real HTTP), AuthHardeningIT (JWT round-trip/wrong-secret; HMAC round-trip/tamper; unsigned + tampered webhooks → 401 over real HTTP) |
| Regression tests for fixed bugs | pin the exact failure mode | ConcurrentIdempotencyClaimIT (16-thread claim: 0 exceptions), PaymentRetryIT (timeout → retry → confirm; concurrent duplicate email registrations) |
| Frontend | component behavior with mocked fetch | vitest: inventory display, sold-out state, hold panel, error mapping, checkout outcome |
| Load (k6) | live system, measured truth | flash-sale (contention), duplicate-request (idempotency), steady-traffic (mixed) |

Principles:
- **Concurrency is tested concurrently** — a sequential test of a race is
  not a test of the race.
- **Shared Postgres container, isolated worlds**: one container for suite
  speed; tests seed unique sections/UUIDs and clean up terminal states so
  reconciliation-style global assertions stay valid.
- **Tests drive jobs synchronously** (expiration, outbox) so assertions
  never race the scheduler.
- **Load testing is a bug-hunting tool, not a numbers generator**: both
  the idempotency 500 and the benchmark-script key bug were found this way.
- Redis/Kafka are excluded in the Postgres-only suite; the full pipeline
  is exercised against compose in the demo and load runs.

## Consequences

- (+) 55 backend + 5 frontend tests, all green; the two nastiest bugs
  have permanent regression pins.
- (−) Suite time (~2–3 min with container startup) — acceptable for the
  confidence; CI runs it on push.
