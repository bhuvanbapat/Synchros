# ADR-008: Reservation and order state machines

**Status:** Accepted

## Context

Illegal states must be unrepresentable: a reservation that is both
expired and confirmed, an order confirmed without payment, a released
hold that stays HELD.

## Decision

Explicit, guarded state machines in the entities; DB CHECK constraints
mirror them.

```
Reservation:  HELD ──confirm──▶ CONFIRMED
              HELD ──expire───▶ EXPIRED
              HELD ──cancel──▶ CANCELLED
              (FAILED reserved for failed hold acquisition)
              CONFIRMED/EXPIRED/CANCELLED/FAILED are terminal

Order:        PENDING_PAYMENT ──success──▶ CONFIRMED
              PENDING_PAYMENT ──failure─▶ FAILED
              PENDING_PAYMENT ──hold lapse──▶ EXPIRED
              PENDING_PAYMENT ──cancel─▶ CANCELLED
```

Rules:
- `transitionTo(target)` validates the edge and throws on illegal moves;
  the throw aborts the surrounding transaction, rolling back any paired
  inventory mutation (guard + tx = atomic decision).
- The expiry job and payment application use **conditional** state flips
  (`WHERE state='HELD'` / lock-then-guard) so a race collapses to exactly
  one winner.
- Terminal states never re-enter. Duplicate confirm is an idempotent
  no-op return; duplicate webhook is recorded, not applied.
- Confirming a lost race surfaces as a *domain* 409
  (RESERVATION_EXPIRED), not an unhandled IllegalStateException — the
  payment flow depends on that mapping to roll back cleanly.

## Consequences

- (+) Every reachable state is legal by construction; reconciliation
  verifies the accounting matches (`total = available + held + sold`).
- (+) Races have exactly one winner with deterministic loser behavior.
- (−) New lifecycle steps (e.g. refunds) require adding a deliberate
  transition + a migration for the CHECK constraint — friction that
  guards correctness.
