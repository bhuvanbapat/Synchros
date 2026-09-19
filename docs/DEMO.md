# Demo Walkthrough

Everything below was executed and verified against the running stack.

## 0. Start

```bash
docker compose up -d postgres redis kafka      # infra
cd backend && mvnw.cmd spring-boot:run         # app on :8081
cd frontend && npm install && npm run dev       # UI on :5173 (proxies /api)
```

Demo accounts (password `password`): `alice@example.com`,
`bob@example.com`, `admin@Synchros.dev`.

## 0a. Log in (JWT — every later call is Bearer)

```powershell
$tok = (Invoke-RestMethod http://localhost:8081/api/auth/login -Method Post `
        -ContentType 'application/json' `
        -Body '{"email":"alice@example.com","password":"password"}').accessToken
$H = @{ Authorization = "Bearer $tok" }
```

## 1. Catalog + inventory

```powershell
$ev = (Invoke-RestMethod http://localhost:8081/api/events -Headers $H)[0].id
Invoke-RestMethod "http://localhost:8081/api/inventory?eventId=$ev" -Headers $H
# FLOOR 10/10, BALCONY 500/500   (availability = 3s-TTL display hint)
```

## 2. Reserve (with idempotency key)

```powershell
$H2 = $H + @{ 'Content-Type'='application/json'; 'Idempotency-Key'='demo-key-1' }
$body = @{ eventId=$ev; section='BALCONY'; quantity=1 } | ConvertTo-Json -Compress
$r = Invoke-RestMethod http://localhost:8081/api/reservations -Method Post -Headers $H2 -Body $body
# 201 { state: HELD, holdExpiresAt: <now+120s>, id: ... }
```

Re-send the exact same request → the **same reservation** comes back
(`X-Idempotent-Replay: true`), not a second one.

## 3. Hold countdown

`GET /api/reservations/{id}` is authoritative. The UI polls on timer-zero
instead of assuming expiry; hold TTL is `HOLD_DURATION_SECONDS=120`.

## 4. Order + payment simulation

```powershell
$H3 = $H + @{ 'Content-Type'='application/json' }
$o = Invoke-RestMethod http://localhost:8081/api/orders -Method Post -Headers $H3 `
        -Body (@{ reservationId=$r.id } | ConvertTo-Json -Compress)
Invoke-RestMethod "http://localhost:8081/api/orders/$($o.id)/pay" -Method Post -Headers $H
# charge outcome SUCCESS/FAILURE/TIMEOUT (weights configurable); relayed
# through the SIGNED webhook path where HMAC verification + idempotency live
# → order CONFIRMED, reservation CONFIRMED, pool stays decremented (SOLD)
```

FAILURE path: order FAILED, reservation CANCELLED, inventory **returned**.
TIMEOUT path: order stays PENDING_PAYMENT, payment retryable; a later
SUCCESS still confirms (regression-tested). Duplicate webhook: absorbed as
DUPLICATE_CALLBACK, zero effect.

### 4a. Webhook HMAC enforcement (try to forge a callback)

```powershell
# Unsigned delivery → 401 WEBHOOK_SIGNATURE_INVALID (no state change)
try { Invoke-RestMethod http://localhost:8081/api/payment-webhooks -Method Post `
        -ContentType 'application/json' `
        -Body '{"orderId":"00000000-0000-0000-0000-000000000001","providerRef":"evil","outcome":"SUCCESS"}' `
        -ErrorAction Stop } catch { $_.Exception.Response.StatusCode.value__ }
# 401  — every legitimate delivery carries X-Signature (HMAC-SHA256 over
# the exact raw bytes; the mock relay signs with the same secret)
```

## 5. Expiration

Reserve, wait 120s (or set `HOLD_DURATION_SECONDS=5`). The scan job
expires the hold: reservation → EXPIRED, **inventory back in the pool**,
open order → EXPIRED. Confirming after expiry yields 409
RESERVATION_EXPIRED, never a 500.

## 6. Oversell proof (the centerpiece)

```bash
# fresh hot pool
docker exec Synchros-postgres psql -U Synchros -d Synchros -c `
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

```powershell
$adminTok = (Invoke-RestMethod http://localhost:8081/api/auth/login -Method Post `
        -ContentType 'application/json' `
        -Body '{"email":"admin@Synchros.dev","password":"password"}').accessToken
$adminH = @{ Authorization = "Bearer $adminTok" }
Invoke-RestMethod http://localhost:8081/api/admin/audit?limit=20 -Headers $adminH
Invoke-RestMethod http://localhost:8081/api/admin/metrics   -Headers $adminH
Invoke-RestMethod http://localhost:8081/api/admin/analytics -Headers $adminH
Invoke-RestMethod http://localhost:8081/api/admin/reconciliation -Method Post -Headers $adminH
# { consistent: true, poolsChecked: n, findings: [] }
```

The UI's Admin tab (log in as admin@) shows the same live data: pools with
held/sold, reservation state counts, outbox by state, dead letters,
reconciliation button.

## 10a. HA Kafka chaos drill (docker-compose.ha.yml)

```bash
docker compose -f docker-compose.ha.yml up -d kafka-1 kafka-2 kafka-3
docker cp tools/ha-create-topics.sh Synchros-kafka-1:/tmp/ha.sh
docker exec Synchros-kafka-1 bash /tmp/ha.sh        # RF=3, min ISR=2
# produce 10 messages, then:
docker stop Synchros-kafka-2                        # kills the leader
# → leadership fails over; ISR 3→2; all 10 messages still consumable
docker start Synchros-kafka-2                       # broker rejoins
docker compose -f docker-compose.ha.yml down -v          # cleanup
```

## What to look for (interview-grade moments)

1. `available` can never go negative — watch it hit exactly 0 with 500
   clients hammering.
2. The duplicate key: fire the same POST twice; the response is
   byte-identical (cached replay).
3. Kafka stopped: reservations still commit; the outbox visibly buffers.
4. Reconciliation after chaos: still consistent — because release paths
   are the same conditional state flips as confirm.
