# Concurrency Control

## The problem

> 100 users attempt to reserve 10 units at the same time.
> The system must never reserve 11.

Naive read-modify-write fails:

```
T1: SELECT available  → 10        T2: SELECT available  → 10
T1: UPDATE available = 9          T2: UPDATE available = 9     ← both wrote 9
T1: INSERT reservation A          T2: INSERT reservation B
→ 11 units "owned", 9 decremented. OVERSOLD.
```

## The chosen control (and why)

### 1. Atomic conditional decrement — the primary gate

```sql
UPDATE inventory_pool
SET available = available - :qty
WHERE id = :poolId AND available >= :qty
```

Why this is airtight:

- PostgreSQL executes an UPDATE as a single atomic operation: it takes an
  **exclusive row lock**, evaluates the predicate under that lock, and
  mutates only if the predicate holds.
- Two concurrent transactions cannot both see `available >= qty` and both
  decrement. The second blocks on the row lock, then **re-evaluates the
  predicate**: if the first committed a decrement, the second's predicate
  is false, 0 rows update, the service maps that to `INVENTORY_UNAVAILABLE`.
- The reservation INSERT + outbox INSERT are in the **same transaction**:
  a hold can never exist without its decrement, and vice versa.

### 2. Database CHECK constraint — the backstop

```sql
available INT NOT NULL CHECK (available >= 0 AND available <= total)
```

Even a hypothetical logic bug cannot push the counter negative —
Postgres rejects the write. `pool.available >= 0` is a database invariant,
not an application promise.

### 3. Pessimistic row locks — for read-modify-write on state machines

The conditional UPDATE covers the *counter*. State *transitions*
(confirm/cancel/create-order) are read-modify-write on the reservation row,
so they take `SELECT ... FOR UPDATE` first:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT r FROM Reservation r WHERE r.publicId = :publicId AND r.userId = :userId")
Optional<Reservation> lockByPublicIdAndOwner(...);
```

This serializes the **confirm-vs-expire race** deterministically:

```
confirm():    lock row → state==HELD? → transitionTo(CONFIRMED) → commit
expire job:   UPDATE ... WHERE state='HELD' (conditional flip)
              → 0 rows if confirm committed first → no release
              → 1 row if it won → confirm's guard throws → tx rollback
```

Exactly one winner; the loser observes a legal 409 (`RESERVATION_EXPIRED`)
and rolls back atomically with its paired inventory mutation.

### 4. Optimistic locking (@Version) — passive defense

Reservation, Order, and InventoryPool rows carry `@Version`. Any lost-update
attempt that slips past the explicit mechanisms fails at commit with
`OptimisticLockException` → transaction rollback → no corruption. It is not
the primary mechanism (retrying a *lost reservation race* would be wrong —
losing means sold out); it exists so that a bug cannot silently corrupt.

## Why NOT the alternatives

| Alternative | Rejected because |
|---|---|
| `SELECT ... FOR UPDATE` on the pool for the whole reservation | serializes the hot row for the entire tx duration including INSERTs — worse throughput, no correctness gain over the single-statement CAS |
| Redis distributed lock (SETNX) | adds a second coordinator that can fail or stall; a lock server does not strengthen a guarantee Postgres already makes atomically; Redis is not the source of truth so it cannot own inventory decisions (ADR-004) |
| SERIALIZABLE everywhere | quarantines whole predicate ranges on hot rows, retry storms under flash-sale contention, and it is unnecessary: the single-row CAS + row locks on state transitions are *narrower and sufficient* |
| Optimistic retry loop on decrement | the failure means "sold out" — retrying would just burn CPU to re-hear "no" |

## Where each mechanism lives

| Operation | Mechanism | Isolation |
|---|---|---|
| reserve (decrement + hold) | conditional UPDATE (CAS) | READ_COMMITTED (default) — sufficient: correctness comes from the row lock the UPDATE itself takes, not isolation |
| confirm / cancel | pessimistic row lock + state machine guard | READ_COMMITTED |
| create order for hold | pessimistic reservation lock (owner-scoped) + TTL recheck | READ_COMMITTED |
| expire holds | conditional single-statement flip `WHERE state='HELD'` | READ_COMMITTED |
| apply payment result | pessimistic order lock + payment state filter | READ_COMMITTED |
| idempotency claim | `INSERT ... ON CONFLICT DO NOTHING` rowcount | READ_COMMITTED |
| reconciliation report | one snapshot of pool rows + reservation rows | **REPEATABLE_READ** — the report must not mix pre/post states of a concurrent commit |

READ_COMMITTED is chosen everywhere it is correct because each mutation is
a single-row guarded statement; bumping isolation would change nothing
except throughput. The one exception is reconciliation, which reads many
rows and must see ONE consistent snapshot (ADR-003).

## Remaining races and how they close

| Possible race | Closure |
|---|---|
| Two confirms for one reservation | row lock serializes; second sees CONFIRMED → idempotent no-op return |
| Confirm vs. expiration | row lock + conditional flip: exactly one winner (ExpirationIT, OrderCreateExpirationRaceIT) |
| Create-order vs. expiration | reservation lock before state check (OrderCreateExpirationRaceIT, 20 rounds × 2 threads: 0 illegal outcomes) |
| Duplicate payment callbacks | order row lock + payment INITIATED filter; losers record DUPLICATE_CALLBACK (PaymentFlowIT: 8 concurrent → 1 effect) |
| Concurrent idempotency claims | ON CONFLICT DO NOTHING rowcount (ConcurrentIdempotencyClaimIT: 16 threads → 1 Fresh, 15 InFlight, 0 errors) |
| Release (expire/cancel) double-crediting | release paths only run after winning the conditional state flip; CHECK bounds the counter anyway |

## Proof (measured, not asserted)

- `OversellPreventionIT`: 100 clients vs 10 units → ≤10 succeed, available
  never negative; repeated 3× with 60 vs 20 — same invariant.
- Live k6 `flash-sale.js` (post-fix code, local machine):
  **500 VUs vs 100 units → 100 successes, 400 clean 409s, 0 oversell,
  0 5xx**; pool ends `available=0`; reconciliation `consistent=true`.
- `OrderCreateExpirationRaceIT`: 20 rounds of racing create-vs-expire +
  8-way concurrent order creation — only legal states.

See docs/PERFORMANCE.md for latency numbers under contention.
