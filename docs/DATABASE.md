# Database Design

PostgreSQL 17. Flyway migrations V1–V5, `ddl-auto: validate` (Hibernate
never creates or drops schema in normal operation).

## Schema (V1 core, V5 removal)

```
fr_user(id, public_id UUID, email UNIQUE, password_hash, role, account_state, created_at)
venue(id, name)
fr_event(id, public_id UUID, venue_id→venue, name, starts_at, on_sale_at, state)
inventory_pool(id, public_id UUID, event_id→fr_event, section,
               total CHECK(total>=0),
               available CHECK(available>=0 AND available<=total),
               version, UNIQUE(event_id, section))
reservation(id, public_id UUID, user_id→fr_user, event_id→fr_event,
            state CHECK(HELD|CONFIRMED|EXPIRED|CANCELLED|FAILED),
            quantity CHECK(quantity>0), section,
            created_at, hold_expires_at, confirmed_at, expired_at,
            cancelled_at, version)
fr_order(id, public_id UUID, user_id, reservation_id UNIQUE→reservation,
         state CHECK(PENDING_PAYMENT|CONFIRMED|FAILED|CANCELLED|EXPIRED),
         amount_cents, currency, created_at, confirmed_at, failed_at, version)
payment(id, public_id UUID, order_id→fr_order, state, amount_cents,
        provider_ref, created_at, completed_at)
payment_attempt(id, payment_id→payment, outcome, provider_ref, created_at)
idempotency_key(id, idem_key, user_id, operation, request_hash,
                response_status, response_body JSONB, state, created_at,
                expires_at, UNIQUE(user_id, operation, idem_key))
outbox_event(id, event_id UUID UNIQUE, event_type, aggregate_type,
             aggregate_id, payload JSONB, state, retry_count, created_at,
             published_at)
processed_event(id, event_id, consumer_group, processed_at,
                UNIQUE(event_id, consumer_group))
dead_letter(id, event_id, event_type, topic, error, payload JSONB,
            attempt_count, created_at)
audit_event(id, actor, operation, entity_type, entity_id, result,
            request_id, details JSONB, created_at)
notification(id, public_id UUID, user_id, kind, body, created_at)
analytics_event(id, event_category, event_type, payload JSONB, created_at)
```

## Indexes (each has a query that justifies it)

| Index | Serves |
|---|---|
| `idx_fr_event_starts_at`, `idx_fr_event_state` | catalog listing |
| `idx_reservation_user (user_id, created_at DESC)` | "my reservations" list |
| `idx_reservation_expire_scan ON reservation (hold_expires_at) WHERE state='HELD'` | expiration job scan — **partial** index: only rows the job can act on |
| `idx_reservation_event (event_id)` | reconciliation per-pool aggregation |
| `idx_order_user (user_id, created_at DESC)` | "my orders" |
| `idx_payment_order (order_id)` | payment lookup by order |
| `idx_attempt_payment (payment_id)` | attempt history |
| `idx_idem_expiry (expires_at)` | purge job |
| `idx_outbox_pending ON outbox_event (created_at) WHERE state IN ('PENDING','FAILED')` | publisher poll — partial, ordered by insertion |
| UNIQUE `(user_id, operation, idem_key)` | idempotency claim collision |
| UNIQUE `(event_id, consumer_group)` on processed_event | consumer dedup |
| UNIQUE `reservation_id` on fr_order | idempotent order creation |
| `idx_audit_entity`, `idx_audit_created` | audit trails |
| `idx_notification_user` | notification inbox |
| `idx_analytics_category` | analytics rollups |

No column is indexed "just in case."

## CHECK constraints as invariants

- `inventory_pool.available >= 0 AND available <= total` — the oversell
  backstop; negative inventory is *unrepresentable*.
- Enum-state columns are CHECKed text (`reservation.state`, `fr_order.state`,
  `payment.state`, `payment_attempt.outcome` incl. `DUPLICATE_CALLBACK` +
  `LATE_CALLBACK` (V4)).
- `quantity > 0`, `amount_cents >= 0/> 0` — no zero/negative payloads.

## Transaction boundaries

| Transaction | Contents | Notes |
|---|---|---|
| Reserve | conditional decrement + reservation INSERT + outbox INSERT + audit | one commit; all-or-nothing |
| Confirm | reservation lock + state flip + outbox + audit | called inside the payment tx — same commit as order confirmation |
| Cancel | reservation lock + state flip + inventory increment + outbox + audit | |
| Expire one | conditional flip + inventory increment + outbox + audit | loser (0 rows) touches nothing |
| Create order | reservation lock + state/TTL checks + order INSERT + payment INSERT + outbox | idempotent via UNIQUE(reservation_id) |
| Apply payment | order lock + payment completion + order flip + reservation confirm/cancel + outbox×2 + audit | rolls back wholly if confirm loses the expiry race |
| Idempotency claim | `INSERT .. ON CONFLICT DO NOTHING` | REQUIRES_NEW, independent of business tx |
| Outbox publish | read batch (RO) / mark one row | **never** spans Kafka I/O |
| Consumer effect | projection insert + processed_event marker | atomic; dedup by marker |
| Reconciliation | reads only | REPEATABLE_READ snapshot |

## Migrations

- `V1__core_schema.sql` — tables, constraints, indexes
- `V2__seed_demo.sql` — venue, 3 users (bcrypt 'password'), 1 event, 2 pools
- `V3__analytics.sql` — analytics projection table
- `V4__payment_attempt_outcome.sql` — add LATE_CALLBACK to outcome CHECK
- `V5__drop_unused_inventory_item.sql` — remove the never-used unit-level
  table (dead schema is dead code)

V1–V4 checksums are frozen (Flyway); all new schema changes are new
migrations.
