# ADR-003: Concurrency control strategy

**Status:** Accepted

## Context

Four mutation families contend: reserve (decrement), confirm/cancel
(state change + possible release), expire (job), and payment application
(order + reservation). A single mechanism for all would be either too
weak or too heavy.

## Decision

Mechanism per access pattern, narrowest-correct-first, default isolation
READ_COMMITTED:

1. **Counter mutations (reserve/release)**: single-statement conditional
   UPDATE (`WHERE available >= :qty`). The row lock the UPDATE takes is
   the serialization point; the predicate re-evaluation under lock is
   the decision.
2. **State-machine transitions (confirm/cancel/create-order)**:
   pessimistic `SELECT FOR UPDATE` on the reservation/order row, then the
   guarded transition. Read-modify-write with validation between reads
   wants a lock, not an optimistic retry — the loser of confirm-vs-expire
   has nothing valid to retry into.
3. **Expiry job**: conditional single-statement flip
   (`WHERE state='HELD'`) — loser (0 rows) touches no inventory.
4. **Idempotency claim**: `INSERT .. ON CONFLICT DO NOTHING` rowcount
   (never exception-based signaling; a flush-time constraint violation
   marks the tx rollback-only before the catch block runs).
5. **Reconciliation**: REPEATABLE_READ — the only place elevated
   isolation is used, because it reads many rows and the report must not
   mix pre/post states of one concurrent commit.
6. **@Version everywhere** as passive backstop: a lost update cannot
   commit silently even if a path above were ever mis-edited.

## Rejected alternatives

- SERIALIZABLE everywhere: predicate-range locks + retry storms exactly
  under flash-sale load; the single-row CAS makes it unnecessary.
- Redis distributed locks: a coordinator that can fail independently adds
  a consistency problem rather than removing one.
- Optimistic retry on decrement: the failure means "sold out"; retrying
  re-hears "no" and burns CPU.
