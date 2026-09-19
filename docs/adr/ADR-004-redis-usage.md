# ADR-004: Redis usage (cache + rate limit, fail-open, never authoritative)

**Status:** Accepted

## Context

Redis is in the stack; the question is what it may and may not decide.

## Decision

Redis serves two roles, both disposable:

1. **Availability display hints** — `cache:avail:{poolId}`, 3s TTL,
   cache-aside on the inventory read path. The reservation path never
   reads it: a stale hint can only cause a pointless 409, never an
   oversell, because the conditional UPDATE is the gate.
2. **Rate limiting** — token bucket per (endpoint-class, user) via a
   single atomic Lua script (lazy refill + consume). Abused traffic is
   throttled before it reaches the reservation transaction.

Both paths **fail open** on Redis absence or error: core correctness
lives in Postgres (ADR-002), so a cache outage must not become an API
outage. Metrics (`Synchros_cache{outcome=error}`,
`Synchros_rate_limit{outcome}`) surface the degradation.

## Consequences

- (+) Killing Redis in the demo changes latency and abuse-resistance, not
  correctness — a great interview moment.
- (+) The rate limiter is atomic under concurrency (Lua), so two requests
  cannot race past the bucket.
- (−) Fail-open rate limiting means a Redis outage also disables abuse
  protection temporarily; accepted because the DB remains protected by
  its own constraints and connection limits.

Explicitly rejected: Redis as inventory counters, distributed locks, or
session/auth state — each makes Redis a consistency participant.
