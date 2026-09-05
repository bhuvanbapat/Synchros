# ADR-007: Idempotency model

**Status:** Accepted

## Context

Clients retry: timeouts, double-clicks, mobile networks. A retried
reservation must not create two holds or two charges.

## Decision

**Scope:** (userId, operation, idemKey) — UNIQUE-constrained. A key is
per-user, per-operation; two users may share a key string independently.

**Fingerprint:** SHA-256 of the canonical request body. Same key + same
body → replay the cached response; same key + different body → 409
IDEMPOTENCY_CONFLICT (the original result is never overwritten).

**Claim protocol** (the subtle part): the claim runs REQUIRES_NEW *before*
the business transaction and uses
`INSERT .. ON CONFLICT DO NOTHING` with rowcount semantics:
- rowcount 1 → Fresh: this request executes and later `complete()`s with
  its response (status + body cached for replays);
- rowcount 0 → InFlight: 409 + `Retry-After: 1` (original still executing);
- failure path → `release()` deletes the IN_FLIGHT row so the same key is
  retryable (sold-out clients can retry without key poisoning).

Exception-based claiming is explicitly banned here: a constraint
violation raised during Hibernate flush marks the transaction rollback-only
before application code can react — the "graceful conflict" path then
dies at commit with `UnexpectedRollbackException`. That exact bug shipped
invisibly through sequential tests and was only exposed by 20-way
concurrent benchmarking (now pinned by ConcurrentIdempotencyClaimIT).

**TTL:** 24h; purgeable via admin endpoint.

**Layered idempotency** (each independent):
- reservation create — idempotency keys (this ADR);
- order create — UNIQUE(reservation_id): same hold → same order;
- payment callback — order lock + INITIATED-payment filter: duplicates
  record DUPLICATE_CALLBACK attempts, zero effect;
- event consumption — (event_id, consumer_group) marker;
- confirm — CONFIRMED-on-confirm is a no-op, not an error.

## Consequences

- (+) Retries are safe at every layer they can occur.
- (+) The claim is a single round trip with no exception-driven control
  flow on the hot path.
- (−) One extra INSERT+SELECT per mutation; trivial next to the
  reservation transaction itself.
