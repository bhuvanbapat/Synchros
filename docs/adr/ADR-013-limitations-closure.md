# ADR-013: Closing the documented-limitations tier

- **Status:** Accepted (2026-09-07)
- **Context:** LIMITATIONS.md enumerated real, ranked gaps: login had no
  brute-force protection; the webhook HMAC had no replay defense (a
  captured valid delivery could be replayed raw); consumer retry counters
  were in-memory (bounded per uptime, not globally); the outbox claim had
  no SKIP LOCKED and no backoff; reconciliation was on-demand only; the
  event-state vocabulary was ignored by the engine; account suspension
  had a constraint but no code path; consumer payloads were trusted
  (silent userId=0 projections); the Kafka loop and Redis behaviors had
  zero automated regression protection; and nothing prevented a
  prod-like boot with the repository's printed placeholder secrets.
- **Decision:** Close them all, each with a mechanism plus a pinned test:
  1. **Versioned webhook signatures** (`t=...,v1=HMAC(secret, t "." raw)`,
     replay tolerance 300s default, legacy format refused) — the
     timestamp is signed material, so a captured delivery cannot be
     refreshed without the secret.
  2. **LoginAttemptLimiter**: 5 failures per (email, ip) → 15-min lockout,
     Redis fixed-window, fail-open (availability of login > strictness of
     a counter; the credential check still runs).
  3. **Durable retry counters** (V6 `consumer_retry`): the dead-letter
     decision is as durable as the dead-letter table.
  4. **Claim-atomic outbox publish lease** (V7 `leased_until`):
     `FOR UPDATE SKIP LOCKED` with the lease predicate in the claim SQL
     (post-LIMIT Java filtering starves the poll — rejected), 60s leases,
     exponential backoff re-leases (2^retry s, cap 60s).
  5. **Scheduled reconciliation** (5 min) exporting findings + a
     consistency gauge — alertable without anyone pressing the button.
  6. **Event-state gate** (`EVENT_NOT_RESERVABLE` 409) for
     CANCELLED/SOLD_OUT/CONCLUDED and pre-sales-window events.
  7. **Admin account suspension endpoint** with audit trail and
     self-lockout refusal; suspension binds on the next request via the
     existing fresh-principal reload.
  8. **Strict payload contracts** (`requiredPositiveLong`): malformed
     payloads fail the handler → retry → dead-letter, never silent
     defaults.
  9. **SecretPolicy**: ≥32 chars always; documented placeholder values
     refused under prod-like profiles (fail-closed boot).
  10. **Real-infrastructure ITs**: KafkaLoopIT (KRaft broker in
      Testcontainers, deterministic host port) proves the full
      publisher→broker→consumer loop; RedisRateLimitCacheIT pins the Lua
      bucket's atomicity, cache TTL, and lockout semantics against live
      Redis.
- **Consequences:** 87 automated tests (up from 55); the live smoke drill
  grew to 15 steps including stale-replay 401 with a fresh-signature
  control, live-Redis lockout 429, and the suspension round-trip; the
  tracing pipeline was verified end-to-end live (span probe + HTTP +
  scheduler spans in the shipped collector). Honest remainders are
  re-documented in LIMITATIONS.md: no consumer-side backoff between
  attempts, X-Forwarded-For trust, no two-replica deployment test,
  reconciliation still read-only, no dashboards/alert rules in-repo.
- **Supersedes:** the corresponding "remaining honesty" bullets of
  ADR-011 (JWT) and ADR-006 (outbox) where this pass closes them.
