# ADR-005: Kafka event architecture (few topics, at-least-once + dedup)

**Status:** Accepted

## Context

Confirmed/expired/cancelled reservations, created/confirmed orders, and
payment outcomes need to reach notification + analytics projections
without coupling the reservation transaction to broker availability.

## Decision

- **Three topics** by aggregate — `reservation-events`, `order-events`,
  `payment-events` — consumers route on `eventType`. Not one topic per
  event type (consumer fan-out noise), not one global topic (ACL/retention
  granularity lost).
- **At-least-once delivery, exactly-once effects**: producer idempotence
  (`acks=all`, `enable.idempotence=true`) removes producer-retry
  duplicates; consumer-side `processed_event (event_id, consumer_group)`
  UNIQUE markers make redeliveries no-ops. Effects + marker commit in one
  transaction.
- **Consumers are projections only** (notifications, analytics). Inventory
  and order state are never mutated by events — they belong to the
  transactional API path.
- Malformed/unidentifiable messages quarantine to `dead_letter`
  immediately; handler errors retry up to 5 attempts then quarantine and
  ACK (poison never blocks a partition).
- Kafka dependency is flag-gated (`synchros.kafka.enabled`) so
  Postgres-only integration tests stay hermetic.

## Consequences

- (+) Broker outage is an ops event, not an incident (outbox buffers).
- (+) Topic count stays explainable; retention/ACLs map to aggregates.
- (+) HA topology verified: `docker-compose.ha.yml` runs a 3-broker KRaft
  cluster (RF=3, min.insync.replicas=2); a live leader-kill drill failed
  over with zero message loss. The single-broker compose remains the dev
  default for footprint reasons.
- (−) In-memory attempt counters for consumer retries reset on restart —
  bounded staleness accepted at portfolio scale (a retry topic would be
  the production upgrade path).
