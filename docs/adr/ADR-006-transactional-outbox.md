# ADR-006: Transactional outbox

**Status:** Accepted

## Context

A confirmed reservation must emit an event. "Commit then publish" loses
the event on a crash between the two; "publish then commit" can emit an
event for a rolled-back mutation. Dual writes to two systems cannot be
made atomic.

## Decision

All business events are rows in `outbox_event`, written **in the same
database transaction** as the mutation that caused them. A scheduled
publisher drains PENDING rows to Kafka and marks them
PUBLISHED/FAILED/DEAD.

Critical transaction-boundary rule discovered during the audit and now
structural: **no broker I/O inside a database transaction**. The publisher
reads a batch in a short read-only transaction, sends with no transaction
open, and marks each row in its own short transaction (programmatic
`TransactionTemplate`, because an annotation on a self-invoked method
silently bypasses the proxy).

Reliability properties:
- event-for-committed-change cannot be lost (same commit);
- Kafka outage buffers in PENDING (business traffic unaffected);
- bounded retries (default 10) then DEAD, surfaced via
  `/api/admin/outbox/dead`;
- consumers dedup by eventId, so publisher-at-least-once is safe.

## Consequences

- (+) The dual-write problem is solved by having one write.
- (+) Broker unavailability demonstrably does not touch the reservation
  path (live-verified: stop container → 201s continue → start → drain).
- (−) Small publish latency (≤ poll interval, default 500ms) — inherent
  to the pattern, irrelevant for notification/analytics consumers.
