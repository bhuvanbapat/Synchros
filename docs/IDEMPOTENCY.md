# Idempotency Design

## The contract

> POST /api/reservations with `Idempotency-Key: abc123`, retried N times
> (concurrently or sequentially), creates exactly ONE logical reservation
> and returns the original result to every retry.

## Scope and identity

A key is scoped to **(userId, operation, idemKey)** — UNIQUE constraint
backed. Two users may use the same key string independently; the same user
using one key for different operations is two operations.

Keys live in `idempotency_key`:

```
idem_key | user_id | operation | request_hash | response_status
response_body JSONB | state IN_FLIGHT|COMPLETED | expires_at (24h)
UNIQUE (user_id, operation, idem_key)
```

## Request fingerprinting

`SHA-256` of the canonical JSON serialization of the request body. Same
key + same body → replay. Same key + **different** body →
`IDEMPOTENCY_CONFLICT` 409 (client bug or key reuse), the original result
is untouched.

## The claim (the hard part)

```
begin(user, op, key, hash)          ← REQUIRES_NEW, before the business tx
  row exists & not expired?
    hash mismatch     → 409 IDEMPOTENCY_CONFLICT
    state COMPLETED   → Replay(cached status, cached body)   → served verbatim
    state IN_FLIGHT   → InFlight                            → 409, Retry-After: 1
  else:
    INSERT ... ON CONFLICT DO NOTHING      ← rowcount decides
    rowcount 0 → InFlight (a concurrent claimant won)
    rowcount 1 → Fresh (this request owns the key)
```

**Why `ON CONFLICT DO NOTHING` and not catch-the-exception:** a constraint
violation raised during Hibernate flush marks the transaction rollback-only
*before* application code can catch it — the "graceful" path then dies at
commit with `UnexpectedRollbackException`. This was a real 500-on-every-
concurrent-duplicate bug, found by the duplicate-request benchmark, fixed
with rowcount semantics, and pinned by `ConcurrentIdempotencyClaimIT`
(16 threads → exactly 1 Fresh, 15 InFlight, 0 exceptions).

## Completion and release

- Success → `complete(claim, 201, body)` persists the response (REQUIRES_NEW)
  and marks COMPLETED — replays serve the cached JSON with
  `X-Idempotent-Replay: true`.
- Failure (validation, sold out, any error) → `release(claim)` **deletes**
  the IN_FLIGHT row so a client may retry the same key for a fresh attempt.
  Sold-out retries therefore stay cheap and legal.

## Lifecycle

- TTL 24h; expired rows are re-claimable and purged by
  `POST /api/admin/maintenance/purge-expired-idempotency` (admin).

## Idempotency vs. rate limiting (distinct by design)

| | Idempotency | Rate limiting |
|---|---|---|
| Answers | "is this a retry of a logical operation?" | "is this client sending too much volume?" |
| Mechanism | DB claim rows + fingerprints | Redis token bucket (Lua, atomic) |
| Failure mode | strict (DB) | fail-OPEN (Redis absent/down) |
| Response on hit | 201 replay / 409 in-flight | 429 RATE_LIMIT_EXCEEDED |

## Verified behavior matrix

| Scenario | Result | Test |
|---|---|---|
| same key + same body, sequential | 1 reservation, replays identical | ApiIdempotencyIT |
| same key + same body, 20 concurrent | exactly 1 reservation; 1×201 + 19×409/429, 0 5xx | k6 duplicate-request (live) |
| same key + different body | 409 IDEMPOTENCY_CONFLICT, original preserved | ApiIdempotencyIT |
| different keys + same body | both execute (distinct operations) | IdempotencyIT |
| 16 threads racing one key | 1 Fresh, 15 InFlight, 0 exceptions | ConcurrentIdempotencyClaimIT |

## Related idempotency (different layers)

- **Order creation**: idempotent per reservation via UNIQUE(reservation_id) —
  same reservation returns the same order.
- **Payment callbacks**: idempotent per providerRef/order — duplicates
  recorded as DUPLICATE_CALLBACK attempts, zero business effect.
- **Kafka consumption**: idempotent per (event_id, consumer_group) marker.
- **Reservation confirm**: idempotent — confirming an already-CONFIRMED
  reservation is a no-op return, not an error.
