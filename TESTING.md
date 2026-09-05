# FlashReserve — How to Test Everything

Complete, executable testing guide — from one-command smoke checks to the
full concurrency proofs. Every command here was run during the final
audit. Copy-paste friendly (PowerShell on Windows; bash equivalents noted).

**Prereqs:** Docker running · Java 25 (`JAVA_HOME` set) · Node 24 ·
k6 installed (https://grafana.com/docs/k6/latest/set-up/install-k6/ — the
binary is intentionally not committed).

---

## 0. Bring the system up

```powershell
cd FlashReserve
docker compose up -d --build app        # postgres + redis + kafka + app
# wait for health:
Invoke-RestMethod http://localhost:8081/actuator/health
# -> {"status":"UP"}
```

Demo accounts (password `password`): `alice@example.com`,
`bob@example.com`, `admin@flashreserve.dev`.

---

## 1. Automated tests (the fastest full verification)

### 1a. Backend — 45 tests incl. real-Postgres concurrency ITs

```powershell
cd backend
$env:JAVA_HOME='C:\Program Files\Java\jdk-25'   # adjust if needed
.\mvnw.cmd test
```

Expected: `Tests run: 45, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`.
Testcontainers pulls its own throwaway Postgres — the compose DB is not
touched. Runtime ≈ 2–3 min (first run downloads images).

What you are actually verifying, by test class:

| Class | The proof |
|---|---|
| OversellPreventionIT | 100 concurrent reservations vs 10 units → at most 10 succeed, availability never negative (runs 3 different sizes) |
| ConcurrentIdempotencyClaimIT | 16 threads racing one idempotency key → exactly 1 wins, 15 get in-flight, **zero exceptions** |
| OrderCreateExpirationRaceIT | order-creation vs expiry, 20 rounds → no orphaned orders; 8 concurrent creates → 1 order row |
| PaymentFlowIT | success/failure/timeout flows; 8 concurrent duplicate callbacks → exactly 1 confirmation |
| PaymentRetryIT | TIMEOUT then SUCCESS still confirms (retry path not bricked); concurrent duplicate registrations → 1 user + 7 clean 400s |
| ExpirationIT | expiry releases units; confirm-vs-expire → one winner; confirm after expiry fails cleanly |
| ApiIdempotencyIT / IdempotencyIT | replay/conflict/in-flight semantics (service + real HTTP) |
| ApiSecurityIT | 401/403 gates, IDOR blocked, injection fails safely, validation 400s |
| OutboxIT | outbox row commits with the mutation, rolls back with it |
| ReconciliationIT | detects intentionally corrupted counters + stuck holds |
| EventHandlersTest / DeadLetterServiceTest | consumer dedup; poison quarantine |
| ReservationStateMachineTest / FingerprintTest | transition guards; request hashing |

Single-class run: `.\mvnw.cmd test "-Dtest=OversellPreventionIT"`

### 1b. Frontend — 5 component tests + build + lint

```powershell
cd frontend
npm install            # once
npm test -- --run --pool=threads     # 5/5 (use --pool=threads: vitest worker timeout on some machines)
npm run build                        # production bundle
npx oxlint src                       # lint, exit 0
```

### 1c. CI parity (what GitHub Actions will run)

```powershell
cd backend  && .\mvnw.cmd --batch-mode clean test
cd backend  && .\mvnw.cmd --batch-mode -DskipTests package
cd frontend && npm ci && npx oxlint src && npm test -- --run --pool=threads && npm run build
```

---

## 2. Manual E2E — the core user journey (5 minutes)

Header helper used below:

```powershell
$auth  = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('alice@example.com:password'))
$H     = @{ Authorization = "Basic $auth" }
$ev    = (Invoke-RestMethod http://localhost:8081/api/events -Headers $H).id
```

### 2.1 Reserve → idempotent replay → pay → confirm

```powershell
# create a hold (note the idempotency key)
$H2 = $H + @{ 'Content-Type'='application/json'; 'Idempotency-Key'='test-1' }
$body = @{ eventId=$ev; section='BALCONY'; quantity=1 } | ConvertTo-Json -Compress
$r = Invoke-RestMethod http://localhost:8081/api/reservations -Method Post -Headers $H2 -Body $body
$r.state        # -> HELD
$r.holdExpiresAt

# send the EXACT same request again — replay, not a second hold:
$r2 = Invoke-RestMethod http://localhost:8081/api/reservations -Method Post -Headers $H2 -Body $body
($r2.id -eq $r.id)   # -> True    (response also carries X-Idempotent-Replay: true)

# order + simulated payment (relay goes through the real webhook path)
$H3 = $H + @{ 'Content-Type'='application/json' }
$o = Invoke-RestMethod http://localhost:8081/api/orders -Method Post -Headers $H3 -Body (@{ reservationId=$r.id } | ConvertTo-Json -Compress)
$c = Invoke-RestMethod "http://localhost:8081/api/orders/$($o.id)/pay" -Method Post -Headers $H3
$c.outcome       # SUCCESS / FAILURE / TIMEOUT (weighted; just retry on TIMEOUT)
$c.orderState    # CONFIRMED when SUCCESS

# reservation now CONFIRMED; availability decremented (not released)
(Invoke-RestMethod "http://localhost:8081/api/reservations/$($r.id)" -Headers $H).state
```

### 2.2 Idempotency edge cases

```powershell
# same key + DIFFERENT body -> 409 IDEMPOTENCY_CONFLICT
Invoke-WebRequest http://localhost:8081/api/reservations -Method Post `
  -Headers ($H + @{ 'Content-Type'='application/json'; 'Idempotency-Key'='test-1' }) `
  -Body (@{ eventId=$ev; section='BALCONY'; quantity=2 } | ConvertTo-Json -Compress) `
  -SkipHttpErrorCheck | Select-Object StatusCode,Content   # 409, code=IDEMPOTENCY_CONFLICT

# no key at all -> two calls create two holds (keys are opt-in per request)
```

### 2.3 Ownership (IDOR is blocked)

```powershell
# bob cannot read alice's reservation
$bobH = @{ Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('bob@example.com:password')) }
Invoke-WebRequest "http://localhost:8081/api/reservations/$($r.id)" -Headers $bobH -SkipHttpErrorCheck |
  Select-Object StatusCode, Content     # 403, {"code":"FORBIDDEN",...}
```

### 2.4 Error contract

```powershell
Invoke-WebRequest http://localhost:8081/api/reservations/00000000-0000-0000-0000-000000000000 -Headers $H -SkipHttpErrorCheck | % Content
# 404 {"code":"NOT_FOUND","message":...,"requestId":...,"timestamp":...}
Invoke-WebRequest http://localhost:8081/api/events -SkipHttpErrorCheck | % Content    # 401
Invoke-WebRequest http://localhost:8081/api/admin/metrics -Headers $H -SkipHttpErrorCheck | % Content   # 403 structured
Invoke-WebRequest http://localhost:8081/api/reservations -Method Post -Headers ($H+@{'Content-Type'='application/json'}) -Body '{bad' -SkipHttpErrorCheck | % StatusCode  # 400
```

### 2.5 Hold expiration (2 minutes, or set `HOLD_DURATION_SECONDS=15` and restart app)

```powershell
$H2['Idempotency-Key'] = 'expiry-1'
$r = Invoke-RestMethod http://localhost:8081/api/reservations -Method Post -Headers $H2 -Body $body
# ...wait past holdExpiresAt...
(Invoke-RestMethod "http://localhost:8081/api/reservations/$($r.id)" -Headers $H).state   # -> EXPIRED
# availability restored — the expiration job released the units
```

Confirm-after-expiry must 409 (never 500):
`POST /api/orders` with that reservation → `RESERVATION_EXPIRED`.

---

## 3. Admin / operations checks

```powershell
$adminH = @{ Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('admin@flashreserve.dev:password')) }

Invoke-RestMethod http://localhost:8081/api/admin/metrics    -Headers $adminH   # pools w/ held+sold, reservation counts, outbox by state
Invoke-RestMethod http://localhost:8081/api/admin/audit     -Headers $adminH   # append-only trail w/ requestId
Invoke-RestMethod http://localhost:8081/api/admin/analytics  -Headers $adminH   # event-type rollups (Kafka consumer output)
Invoke-RestMethod http://localhost:8081/api/users/me/notifications -Headers $H # alice's inbox (ReservationConfirmed etc.)
Invoke-RestMethod http://localhost:8081/api/admin/reconciliation -Method Post -Headers $adminH
# -> consistent=True, findings=[]        <- run this after ANY chaos below
```

Pool invariant straight from SQL (the ultimate truth, cache excluded):

```powershell
docker exec flashreserve-postgres psql -U flashreserve -d flashreserve -c `
 "SELECT p.section, p.total, p.available,
   COALESCE(SUM(CASE WHEN r.state='HELD' THEN r.quantity END),0) AS held,
   COALESCE(SUM(CASE WHEN r.state='CONFIRMED' THEN r.quantity END),0) AS sold
  FROM inventory_pool p
  LEFT JOIN reservation r ON r.event_id=p.event_id AND r.section=p.section
  GROUP BY p.section, p.total, p.available ORDER BY p.section;"
# For every row: available + held + sold MUST equal total.
```

---

## 4. The headline proof — oversell benchmark

Create a fresh hot pool, fire 500 concurrent clients at 100 units:

```powershell
docker exec flashreserve-postgres psql -U flashreserve -d flashreserve -c `
  "INSERT INTO inventory_pool (event_id, section, total, available)
   SELECT 1,'HOTBENCH',100,100
   WHERE NOT EXISTS (SELECT 1 FROM inventory_pool WHERE section='HOTBENCH');
   UPDATE inventory_pool SET available=total WHERE section='HOTBENCH';"
```

k6 on OneDrive paths fails relative imports — copy to a plain path:

```powershell
$run = "$env:TEMP\k6run"; New-Item -ItemType Directory -Force -Path $run | Out-Null
Copy-Item load-tests\*.js $run -Force
k6 run "-e EVENT_ID=$ev" -e SECTION=HOTBENCH -e CLIENTS=500 -e UNITS=100 "$run\flash-sale.js"
```

**Pass criteria (all must hold):**

```
flashreserve_reservation_success .........: 100     <- exactly UNITS, never more
http_req_failed ..........................: 0.00%   <- no 5xx/timeout (409/429 are expected statuses)
final pool: available = 0, never negative (SQL check in §3)
reconciliation: consistent=True
```

Duplicate-request benchmark (20 VUs, one key → one reservation):

```powershell
k6 run "-e EVENT_ID=$ev" -e SECTION=BALCONY -e RUN_TAG="run-$(Get-Date -Format HHmmss)" "$run\duplicate-request.js"
# checks_succeeded = 100%; DB reservation count for BALCONY must rise by exactly 1
```

---

## 5. Failure drills (the ones that matter)

### 5.1 Kafka outage → buffer → drain (no lost events)

```powershell
$before = (Invoke-RestMethod http://localhost:8081/api/admin/metrics -Headers $adminH).outbox

docker compose stop kafka

# business as usual — reserve + pay (§2.1). Everything must still return 201/CONFIRMED.
# outbox now buffers: PENDING rises, FAILED rows show retry_count growing, DEAD stays 0
Invoke-RestMethod http://localhost:8081/api/admin/metrics -Headers $adminH | % outbox

docker compose start kafka
# within ~30s: PENDING -> 0, FAILED -> 0, PUBLISHED rose by exactly the buffered count
Invoke-RestMethod http://localhost:8081/api/users/me/notifications -Headers $H   # consumer caught up
```

**Fail =** any 5xx during the outage, DEAD > 0, or PUBLISHED delta ≠
buffered count.

### 5.2 Redis down → core correctness unaffected

```powershell
docker compose stop redis
# reserve + pay must still work (rate limiting + hints fail OPEN by design)
# /api/health shows redis: DOWN — that's expected and informational
docker compose start redis
```

### 5.3 Duplicate payment callback (direct webhook)

```powershell
$H2['Idempotency-Key'] = 'dup-pay-1'
$r = Invoke-RestMethod http://localhost:8081/api/reservations -Method Post -Headers $H2 -Body $body
$o = Invoke-RestMethod http://localhost:8081/api/orders -Method Post -Headers $H3 -Body (@{ reservationId=$r.id } | ConvertTo-Json -Compress)

# fire the SAME webhook twice back-to-back
1..2 | % { Invoke-RestMethod http://localhost:8081/api/payment-webhooks -Method Post `
  -Headers @{ 'Content-Type'='application/json' } `
  -Body (@{ orderId=$o.id; providerRef='manual-ref-1'; outcome='SUCCESS' } | ConvertTo-Json -Compress) }

(Invoke-RestMethod "http://localhost:8081/api/orders/$($o.id)" -Headers $H).state  # CONFIRMED (once)
# inventory decremented exactly once (SQL §3), attempt row records DUPLICATE_CALLBACK
```

Malformed webhook → 400, no state change:
`-Body '{"orderId":null,"providerRef":"","outcome":"NOPE"}'`

---

## 6. Frontend testing

```powershell
cd frontend
npm run dev          # http://localhost:5173 (proxies /api -> 8081)
```

Manual checklist mirroring the automated tests:
1. Login with a wrong password → error shown, no entry.
2. Inventory table renders totals/availability; sold-out sections show
   "Sold out" disabled.
3. Reserve → hold panel with **countdown**; at 0 it refetches and shows
   the backend's actual state (EXPIRED), never a client guess.
4. During HELD: Pay → outcome badge (SUCCESS/FAILURE/TIMEOUT); Cancel →
   state CANCELLED, availability restored.
5. Trigger INVENTORY_UNAVAILABLE (reserve the 10-unit FLOOR 11×) →
   the error code is surfaced.
6. Log in as `admin@flashreserve.dev` → Admin tab: live pool numbers,
   reservation/outbox counts, dead letters, Run reconciliation → result line.

Automated: `npm test -- --run --pool=threads` (5 tests cover items 2–5).

---

## 7. Full-suite acceptance (the exact order used in the final audit)

```powershell
# 1. automated backend + frontend
cd backend  && .\mvnw.cmd --batch-mode clean test          # 45/45
cd frontend && npm ci && npx oxlint src &&
               npm test -- --run --pool=threads && npm run build

# 2. fresh stack
docker compose up -d --build app
Invoke-RestMethod http://localhost:8081/actuator/health   # UP

# 3. journey + drills (§2, §5)
# 4. benchmarks (§4)
# 5. final word
Invoke-RestMethod http://localhost:8081/api/admin/reconciliation -Method Post -Headers $adminH
# -> consistent=True, findings=[]
```

**The project passes when:** 45+5 automated tests green · §2 journey
returns HELD/CONFIRMED with idempotent replays · 500-VU benchmark sells
exactly 100 with 0 errors · both drills (§5.1, §5.2) keep commits flowing
and reconcile clean at the end.

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Port 8081 busy | Old instance running: `Get-NetTCPConnection -LocalPort 8081 -State Listen` → `Stop-Process -Id <pid> -Force` |
| Testcontainers can't start | Docker not running / WSL memory limits — start Docker Desktop first |
| vitest "worker did not respond" | Use `--pool=threads` (OneDrive + worker processes) |
| k6 panic on helpers.js | URL-encoded path — copy scripts to a plain path (§4) |
| 429 during manual tests | Per-user rate limit (30/min) — wait a minute or raise `RATE_LIMIT_BURST` |
| FLOOR pool sold out from testing | `UPDATE inventory_pool SET available=total WHERE section='FLOOR';` (demo DB only) |
