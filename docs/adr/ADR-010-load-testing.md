# ADR-010: Load-testing strategy

**Status:** Accepted

## Context

Performance claims must be measured, and — more importantly — contention
is where the bugs that sequential testing misses live.

## Decision

k6 with three scenario scripts sharing helpers (JWT login + per-VU token
cache; business-outcome counters distinct from k6 protocol metrics):

1. **flash-sale.js** — extreme contention: N fresh users (parallel batch
   registration in setup) race one pool. Success criterion is the
   *business* invariant: `successes ≤ units`, never a 5xx; expected
   statuses (201/409/429) are declared so designed rejections don't count
   as transport failures.
2. **duplicate-request.js** — idempotency: one run-scoped key (via env —
   k6 executes top-level code per VU, a `Date.now()` constant here gave
   every VU a different key, an actual bug we hit and fixed) fired by 20
   VUs concurrently; DB-verifiable "exactly one reservation".
3. **steady-traffic.js** — mixed browse/reserve ramp for latency profile
   under normal operation.

Principles:
- **Business counters are the exit criteria**, not k6's `http_req_failed`
  (409 INVENTORY_UNAVAILABLE is correct behavior, not a failure).
- **Absolute numbers are environment-bound** and reported with hardware
  context (docs/PERFORMANCE.md); invariants are the portable result.
- Load testing runs **found two real bugs** (idempotency claim 500s; the
  per-VU key script bug) and two hotspots (outbox tx across Kafka sends;
  admin metrics N+1) — the strategy is judged by that yield.

## Consequences

- (+) Every correctness claim in the README traces to a script anyone can
  re-run.
- (−) Single-machine results; no distributed load generator. Documented —
  the architecture arguments stand on the invariants, with latency clearly
  labeled as local measurements.
