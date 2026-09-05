# Interview Guide

Deep-dive Q&A. Every answer reflects what this codebase actually does.

## Q: How do you prevent overselling?

Single-statement atomic conditional update inside the reservation
transaction:

```sql
UPDATE inventory_pool SET available = available - :qty
WHERE id = :poolId AND available >= :qty
```

Postgres takes the exclusive row lock, re-evaluates the predicate under
it, and 0-rows-updates when inventory ran out. The reservation INSERT and
the outbox event are in the same transaction, so a hold can never exist
without its decrement. A CHECK constraint (`available >= 0`) makes
negative inventory unrepresentable even against logic bugs. Proven by
OversellPreventionIT (100 vs 10) and a live 500-VU benchmark: exactly 100
successes, 0 oversell.

## Q: Why PostgreSQL as the authority?

The invariant ("available ≥ 0 and ownership only via committed holds") is
data. Databases give me atomic single-statement CAS, durable commits,
UNIQUE constraints (orders, idempotency keys, event markers), and
isolation knobs in one battle-tested box. Any external coordinator (Redis,
application locks) is a second system that can disagree with the first —
coherence problems worse than the original. Everything correctness-critical
lives in one set of tables; everything else (cache, streams) is disposable.

## Q: Why isn't Redis the source of truth?

Redis is a cache and a rate limiter here. If it holds "availability", a
Redis failover loses counter edits → oversell or spurious sellouts. So it
holds only 3s-TTL display hints and token buckets, both fail-open. When
Redis dies, reservations still work correctly (slower, unthrottled). That
is the test: kill the component, does correctness survive? For Redis in
this design, yes — by construction.

## Q: Optimistic vs pessimistic locking — where and why?

- The hot counter: neither classic pattern — a single-statement **CAS**
  (conditional UPDATE), which is both correct and one round trip.
- State-machine transitions (confirm/cancel/create-order): **pessimistic**
  row locks. The read and the write are separated by validation logic; a
  version-based optimistic retry would either corrupt or need arbitrary
  retries — and the loser's retry would be wrong anyway (the hold expired:
  there is nothing to retry into).
- @Version on entities is a passive backstop: a lost update cannot commit
  silently.

## Q: What happens under 1,000 concurrent requests on one item?

The row lock serializes them: ~1,000 sequential CAS executions (sub-ms
each) plus connection-queue time. Losers get fast 409s once the predicate
breaks. The single hot row is the throughput ceiling — measured p95 22s at
500 VUs on a dev machine is dominated by queueing, not the UPDATE. The
conditional update *fails fast*, so most of that queueing is at the
connection pool, not inside transactions.

## Q: How would you scale inventory beyond one hot row?

- Multiple pools per section (shards): route clients by hash; capacity =
  shards × pool size. Trades global fairness for parallelism.
- Queue-fronted sales: admit N concurrent entrants, reject the rest
  *before* they touch the DB (virtual waiting room) — flash sales are
  admission problems, not throughput problems.
- Batch decrements: a tier of in-memory permits refilled transactionally
  from the pool — only if a single row ever proves insufficient; adds
  recovery complexity (permits must be re-derivable from the DB).

## Q: What happens when Kafka is down?

Exactly nothing, from the user's perspective. Business transactions
commit with an outbox row (same tx). The publisher fails, rows stay
PENDING, retries continue. When Kafka returns, the backlog drains.
Verified live: stop container → reservations 201 → start container →
PUBLISHED count rises to match, FAILED/DEAD stay 0. Two design details
make this safe: the outbox write is in the business tx, and the publisher
never holds a DB transaction during broker I/O.

## Q: Why the outbox pattern at all?

"Commit, then publish" has a failure window: crash between commit and
send loses the event forever; send-then-commit can publish an event for a
rolled-back mutation. The outbox closes this by making the event part of
the transaction — the dual-write problem is solved by having only one
write.

## Q: What if an event is delivered twice?

Consumers insert a `(event_id, consumer_group)` marker in the same
transaction as the effect. Duplicate delivery hits the UNIQUE constraint
→ no re-effect. Kafka is at-least-once *by design* (rebalance, retries);
the system is correct under it rather than pretending it won't happen.
Producer idempotence removes broker-side duplicates from producer
retries, but consumer dedup remains mandatory for redeliveries.

## Q: What if a payment callback is duplicated?

The webhook path locks the order row, so callbacks serialize. The second
sees a terminal payment → records a DUPLICATE_CALLBACK attempt row
(audit-visible) and returns the current state. Concurrent 8-way duplicate
callbacks tested: exactly one confirmation. The relay deliberately makes
duplicate delivery possible to exercise this.

## Q: Reservation expires at the same moment it's confirmed — what happens?

Both paths are conditional on state=HELD: confirm takes the row lock and
flips HELD→CONFIRMED; the expiry job's UPDATE flips only from HELD.
Whichever wins, the other observes 0-rows/illegal-transition and rolls
back its paired mutation. Final state is always one of {CONFIRMED, not
released} or {EXPIRED, released} — never "confirmed and released".
Tested with concurrent rounds; every outcome was legal.

## Q: Why isn't SERIALIZABLE used everywhere?

It's the biggest hammer with real costs: predicate-range locks on hot
rows, serialization failures, retry storms precisely under flash-sale
load. My mutations are single-row guarded statements — READ_COMMITTED
plus row locks is *sufficient*, which I can argue per path. One exception:
reconciliation runs REPEATABLE_READ because it must read many rows from
one snapshot for its report to be self-consistent. "Narrowest correct
mechanism" is the rule; SERIALIZABLE everywhere is correctness by
blanket, with throughput as the casualty.

## Q: Where are the consistency boundaries?

Strong/transactional: inventory ownership, reservation/order/payment state
changes, idempotency records, outbox writes. Eventually consistent:
notifications, analytics (Kafka projections), UI availability hints
(3s TTL). The demo shows a user reading "available" that's 3s stale,
attempting, and getting a clean 409 — the cache can *disappoint*, never
*oversell*.

## Q: What does reconciliation check?

For each pool: `total == available + Σ(HELD qty) + Σ(CONFIRMED qty)`, plus
negative/out-of-range counters, HELD rows stuck past TTL (job stalled),
and PENDING_PAYMENT orders on non-HELD reservations (orphans). It found
deliberately corrupted data in tests (ReconciliationIT) and reported
`consistent=true, 0 findings` after every benchmark.

## Q: What did load testing actually find?

Two real bugs and two hotspots:
(1) concurrent idempotency claims 500'd (rollback-only trap — fixed with
ON CONFLICT rowcount semantics);
(2) the outbox publisher pinned connections across Kafka sends (restructured
transactions);
(3) admin metrics did full-table scans + N+1 (grouped queries);
(4) the expiration scan was unbounded-then-limited (query-level Limit).
That's the point of load testing — not numbers for a README, but the
failure modes you only meet under contention.

## Q: How would you deploy this for real?

App is stateless → N replicas behind a load balancer against managed
Postgres (primary + replica, PITR), a 3-broker Kafka (RF=3, min.insync=2,
outbox absorbs failover windows), Redis with a replica (fail-open anyway).
Migrations via Flyway at deploy; rolling restarts thanks to graceful
shutdown. The outbox gives an async catch-up path, so brief broker
unavailability is an ops event, not an incident.

## Q: What would change at one million concurrent users?

Admission control first (waiting room; most of those million must be
rejected at the edge — that's capacity *policy*, not capacity *hardware*).
Then: sharded pools per section, read replicas for catalog, availability
hints served from Redis only (never the DB), reservation workers
decoupled via a durable queue with the same conditional-decrement at the
DB, and geo-partitioning of events (an event's inventory is naturally
local). The invariants stay identical — they're the cheap part; the
admission architecture is the work.
