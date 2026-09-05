# Demo Walkthrough

Everything below was executed and verified against the running stack.

## 0. Start

```bash
docker compose up -d postgres redis kafka      # infra
cd backend && mvnw.cmd spring-boot:run         # app on :8081
cd frontend && npm install && npm run dev       # UI on :5173 (proxies /api)
```

Demo accounts (password `password`): `alice@example.com`,
`bob@example.com`, `admin@flashreserve.dev`.

## 1. Catalog + inventory

```bash
$H = @{ Authorization = 'Basic ' + [Convert]::ToBase64String(
        [Text.Encoding]::ASCII.GetBytes('alice@example.com:password')) }
$ev = (Invoke-RestMethod http://localhost:8081/api/events -Headers $H).id
Invoke-RestMethod "http://localhost:8081/api/inventory?eventId=$ev" -Headers $H
# FLOOR 10/10, BALCONY 500/500   (availability = 3s-TTL display hint)
```

## 2. Reserve (with idempotency key)

```bash
$H2 = $H + @{ 'Content-Type'='application/json'; 'Idempotency-Key'='demo-key-1' }
$body = @{ eventId=$ev; section='BALCONY'; quantity=1 } | ConvertTo-Json -Compress
Invoke-RestMethod http://localhost:8081/api/reservations -Method Post -Headers $H2 -Body $body
# 201 { state: HELD, holdExpiresAt: <now+120s>, id: ... }
```

Re-send the exact same request → the **same reservation** comes back
(`X-Idempotent-Replay: true`), not a second one.

## 3. Hold countdown

`GET /api/reservations/{id}` is authoritative. The UI polls on timer-zero
instead of assuming expiry; hold TTL is `HOLD_DURATION_SECONDS=120`.

## 4. Order + payment simulation

```bash
$H3 = $H + @{ 'Content-Type'='application/json' }
$o = Invoke-RestMethod http://localhost:8081/api/orders -Method Post -Headers $H3 `
        -Body (@{ reservationId=$r.id } | ConvertTo-Json -Compress)
Invoke-RestMethod "http://localhost:8081/api/orders/$($o.id)/pay" -Method Post -Headers $H3
# charge outcome SUCCESS/FAILURE/TIMEOUT (weights configurable); relayed
# through the webhook path where idempotency lives
# → order CONFIRMED, reservation CONFIRMED, pool stays decremented (SOLD)
```

FAILURE path: order FAILED, reservation CANCELLED, inventory **returned**.
TIMEOUT path: order stays PENDING_PAYMENT, payment retryable; a later
SUCCESS still confirms (regression-tested). Duplicate webhook: absorbed as
DUPLICATE_CALLBACK, zero effect.

## 5. Expiration

Reserve, wait 120s (or set `HOLD_DURATION_SECONDS=5`). The scan job
expires the hold: reservation → EXPIRED, **inventory back in the pool**,
open order → EXPIRED. Confirming after expiry yields 409
RESERVATION_EXPIRED, never a 500.

## 6. Oversell proof (the centerpiece)

```bash
# fresh hot pool
docker exec flashreserve-postgres psql -U flashreserve -d flashreserve -c `
  "INSERT INTO inventory_pool (event_id, section, total, available) VALUES (1,'HOT100',100,100);"
k6 run -e EVENT_ID=$ev -e SECTION=HOT100 -e CLIENTS=500 -e UNITS=100 load-tests/flash-sale.js
```

Measured result: **100 successes, 400 clean 409s, 0 5xx, final available=0.**
No oversell under 500-way contention.

## 7. Duplicate-request proof

```bash
k6 run -e EVENT_ID=$ev -e SECTION=BALCONY -e RUN_TAG=run1 load-tests/duplicate-request.js
# 20 concurrent identical requests → exactly 1 reservation (DB-verifiable)
```

## 8. Kafka down → business unaffected → drains on recovery

```bash
docker compose stop kafka
# reserve + pay: still 201s — commits are Postgres-only
docker compose start kafka
# outbox drains; verify:
Invoke-RestMethod http://localhost:8081/api/admin/metrics -Headers $adminH
# outbox: PENDING drops to 0, PUBLISHED rises, FAILED/DEAD stay 0
```

## 9. Events → consumers → projections

After any confirmed reservation: `GET /api/users/me/notifications` shows
the `ReservationConfirmed` notification produced by the Kafka consumer
(eventually consistent, typically <1s with the local broker).

## 10. Audit + admin + reconciliation

```bash
$adminH = @{ Authorization = 'Basic ' + [Convert]::ToBase64String(
        [Text.Encoding]::ASCII.GetBytes('admin@flashreserve.dev:password')) }
Invoke-RestMethod http://localhost:8081/api/admin/audit?limit=20 -Headers $adminH
Invoke-RestMethod http://localhost:8081/api/admin/metrics   -Headers $adminH
Invoke-RestMethod http://localhost:8081/api/admin/analytics -Headers $adminH
Invoke-RestMethod http://localhost:8081/api/admin/reconciliation -Method Post -Headers $adminH
# { consistent: true, poolsChecked: n, findings: [] }
```

The UI's Admin tab (log in as admin@) shows the same live data: pools with
held/sold, reservation state counts, outbox by state, dead letters,
reconciliation button.

## What to look for (interview-grade moments)

1. `available` can never go negative — watch it hit exactly 0 with 500
   clients hammering.
2. The duplicate key: fire the same POST twice; the response is
   byte-identical (cached replay).
3. Kafka stopped: reservations still commit; the outbox visibly buffers.
4. Reconciliation after chaos: still consistent — because release paths
   are the same conditional state flips as confirm.
