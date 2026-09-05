# FlashReserve Architecture

## System overview

Modular monolith (ADR-001): one Spring Boot application with hard module
boundaries, one PostgreSQL, one Redis, one Kafka broker. Nothing is
"distributed" for show — the distribution that exists is exactly:

- **asynchronous**: business transaction → outbox → Kafka → consumers
  (notifications / analytics projections)
- **transactional (strong)**: all inventory ownership mutations
- **stateless app nodes**: any instance count behind a load balancer

```
                 ┌────────────────────────────────────────────┐
                 │                Browser / k6                │
                 │   React+TS SPA  ·  admin ops dashboard     │
                 └──────────────┬─────────────────────────────┘
                                │ HTTP Basic + JSON
                                ▼
┌──────────────────────────────────────────────────────────────────┐
│                     FlashReserve (Spring Boot)                    │
│                                                                  │
│  api layer          controllers + DTOs + validation + errors     │
│  ────────────────────────────────────────────────────────────   │
│  reservation        state machine HELD→{CONFIRMED|EXPIRED|        │
│                      CANCELLED}, conditional UPDATEs, row locks  │
│  inventory          pooled counters; atomic decrement (the CAS)  │
│  order/payment      order state machine; mock gateway + webhook  │
│  idempotency        ON CONFLICT DO NOTHING key claims (24h TTL)   │
│  outbox             PENDING → PUBLISHED | FAILED → DEAD          │
│  ────────────────────────────────────────────────────────────   │
│  cross-cutting      security, rate limit (Redis Lua), audit,      │
│                     metrics (Micrometer), request-id MDC         │
└───────┬──────────────────┬───────────────────┬───────────────────┘
        │ JDBC            │ LETTUCE           │ Kafka client
        ▼                 ▼                   ▼
┌──────────────┐  ┌──────────────┐  ┌───────────────────────────┐
│ PostgreSQL   │  │ Redis        │  │ Kafka (KRaft)             │
│ AUTHORITY    │  │ availability │  │ reservation-events        │
│ inventory,   │  │ hints (3s    │  │ order-events               │
│ reservations,│  │ TTL), token- │  │ payment-events            │
│ orders,      │  │ bucket rate  │  │                           │
│ payments,    │  │ limit, never │  │ consumers: notification +  │
│ outbox, audit│  │ authoritative │  │ analytics projections     │
└──────────────┘  └──────────────┘  └───────────────────────────┘
```

## Consistency boundaries (explicit)

| Data | Consistency | Mechanism |
|---|---|---|
| Inventory ownership (pool counters) | **Strong / transactional** | single-statement conditional `UPDATE ... WHERE available >= qty` + CHECK constraint, committed atomically with the reservation row |
| Reservation / order state | **Strong** | explicit state machines + pessimistic row locks on state transitions |
| Idempotency records | **Strong** | DB rows, UNIQUE(user, op, key), claim via `ON CONFLICT DO NOTHING` |
| Notifications | **Eventually consistent** | Kafka projection from `ReservationConfirmed/Expired/Cancelled` |
| Analytics feed | **Eventually consistent** | Kafka projection, consumer-side dedup |
| Availability shown in UI | **Hint only** | Redis 3s TTL cache-aside; a stale value can cause a harmless 409, never an oversell |
| Rate limit decisions | **Best effort** | Redis token bucket; fails OPEN (Postgres constraints remain the correctness gate) |

## Event flow (transactional outbox)

```
POST /api/reservations
  tx1:  UPDATE inventory_pool ... WHERE available >= qty   ┐
        INSERT reservation                                 ├─ ONE commit
        INSERT outbox_event (PENDING)                      ┘
  (commit)
  claim idempotency key completes (separate short tx)

OutboxPublisher (every 500ms):
  read batch (short read-only tx)
  for each: kafka.send(...).get()          ← NO tx held during I/O
  mark row PUBLISHED (short tx by id)      ← or FAILED→retry / DEAD

EventConsumers (per topic):
  parse → dedup check → [tx: effect + processed_event marker]
  poison after 5 attempts → dead_letter (REQUIRES_NEW)
```

Kafka producer runs with `acks=all` + `enable.idempotence=true`; consumers
additionally dedup by `(event_id, consumer_group)` UNIQUE. Delivery
semantics end-to-end: at-least-once with exactly-once *effects*.

## Module boundaries

`catalog · inventory · reservation · order · payment · idempotency · outbox
· kafka · notification · audit · analytics · reconciliation · ratelimit ·
cache · security · common · config · metrics · health · admin`

Dependencies point inward: controllers → services → repositories. No
service calls another service's repository. Kafka consumers are the only
sanctioned path for cross-aggregate *reactions* (never for inventory
ownership).

## Failure modes and behavior

| Failure | Behavior |
|---|---|
| Kafka down | business transactions commit normally; outbox rows stay PENDING; publisher retries; drains on recovery (verified live: 537 published, 0 lost) |
| Redis down | rate limiter + availability cache fail OPEN; core reservation correctness unaffected |
| Payment gateway timeout | payment stays INITIATED (retryable); hold TTL is the safety net that releases inventory |
| Duplicate webhook | second delivery recorded as DUPLICATE_CALLBACK attempt; zero business effect |
| Webhook after order expired | absorbed as LATE_CALLBACK (payment marked TIMED_OUT = would-be refund); no state corruption |
| Confirm racing expiration | row lock + state machine: exactly one winner, illegal outcome impossible (tested 20 rounds) |
| Poison event | 5 attempts → dead_letter table → admin endpoint |
| App crash mid-reservation | tx atomicity: either the hold+decrement+outbox all committed, or none did |

## Deployment

`docker compose up --build` starts postgres, redis, kafka, app (backend).
Frontend dev server proxies `/api` to :8081. App is stateless — scale
horizontally by running more app replicas against the same Postgres/Kafka.
