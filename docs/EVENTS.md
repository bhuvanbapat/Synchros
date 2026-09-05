# Event Architecture

## Topics (deliberately few — ADR-005)

| Topic | Events |
|---|---|
| `reservation-events` | ReservationCreated, ReservationConfirmed, ReservationCancelled, ReservationExpired |
| `order-events` | OrderCreated, OrderConfirmed |
| `payment-events` | PaymentSucceeded, PaymentFailed, PaymentTimedOut |

One topic per aggregate, not per event type. Consumers route on `eventType`.

## Envelope

```json
{
  "eventId": "uuid (the outbox row's event id)",
  "eventType": "ReservationConfirmed",
  "aggregateType": "reservation",
  "aggregateId": "reservation public id",
  "occurredAt": "publish time",
  "payload": { ... }
}
```

`eventId` is the identity used for end-to-end dedup.

## Producer path: transactional outbox

```
business tx:  mutation + INSERT outbox_event(PENDING)   ← same commit
publisher:    poll PENDING/FAILED (batch 50, every 500ms)
              kafka.send(acks=all, idempotent).get()     ← no tx open here
              mark PUBLISHED | FAILED (retry) | DEAD     ← short tx per row
```

Guarantees:

- An event for a committed change **cannot be lost**: it exists in the
  same commit as the mutation.
- Kafka being down does NOT stall business traffic: the publisher holds
  no DB transaction during sends (fixed: the earlier version held one tx
  across up to 50 blocking sends and could exhaust the pool during an
  outage).
- Bounded retries (`OUTBOX_MAX_RETRIES`, default 10) then `DEAD` state —
  visible at `GET /api/admin/outbox/dead`.
- Producer idempotence (`enable.idempotence=true`, `acks=all`): producer
  retries cannot create broker-side duplicates.

Verified live: 537 events published, 0 failed, 0 dead across the full
demo + benchmarks.

## Consumer path

```
listener (EventConsumers)
  parse JSON            → malformed → dead_letter (immediately)
  require UUID eventId  → invalid   → dead_letter (immediately)
  dispatch to EventHandlers (separate bean — @Transactional must not be
  self-invoked or the proxy is bypassed)
    handler tx:
      processed_event marker exists? → duplicate → no-op
      else: projection effect + INSERT marker  ← atomic
  handler throws:
    attempts tracked in-memory per (topic, eventId)
    < 5 attempts → rethrow → Kafka redelivery
    ≥ 5 attempts → dead_letter row (REQUIRES_NEW) + ACK
```

Consumer idempotency is **mandatory**: Kafka delivery is at-least-once by
design; the `processed_event (event_id, consumer_group)` UNIQUE marker
makes reprocessing harmless. A `PaymentSucceeded` delivered twice
confirms the order exactly once (unit-tested in EventHandlersTest,
integration-tested in PaymentFlowIT with 8 concurrent duplicates).

## Projections (eventually consistent)

| Consumer | Effect |
|---|---|
| reservation-events | user notification rows for Confirmed/Expired/Cancelled |
| reservation/order/payment-events | analytics_event feed rows |

Projections never touch inventory or order state — those are transactional
concerns of the API path only.

## Dead letters

`dead_letter(event_id, event_type, topic, error, payload, attempt_count)`
— written after bounded retries or immediately for malformed input.
`GET /api/admin/dead-letters` exposes them (admin role). DLQ rows are
operational data for replay tooling, not a retry loop.

## Failure semantics summary

| Failure | Behavior |
|---|---|
| Kafka unavailable at publish time | outbox row stays PENDING; drains on recovery |
| Broker loses the send | producer retries; idempotence dedups |
| Consumer throws transiently | Kafka redelivery; ≤5 attempts |
| Poison message | quarantined to dead_letter; partition keeps flowing |
| Duplicate delivery | processed_event marker: zero re-effects |
