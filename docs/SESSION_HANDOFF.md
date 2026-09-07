# SESSION HANDOFF — READ THIS FIRST (before touching ANY code)

This file exists so a fresh session NEVER re-diagnoses solved problems,
NEVER re-breaks fixed code, and NEVER "fixes" what isn't broken.
Every past mistake, trap, environment gotcha, and hard-won fact is below.

**THE RULE:** If a problem you're hitting is documented here as FIXED or
as a KNOWN TRAP, do NOT rediscover it, do NOT refactor the fix, do NOT
"improve" working code. If you cannot name the exact line and mechanism
you're changing and why, YOU DO NOT MODIFY IT. Verification commands for
every claim are at the bottom — run them, don't re-derive them.

---

## 0. Environment gotchas (will waste hours if unknown)

| Trap | The fix / rule |
|---|---|
| `JAVA_HOME` is NOT set in this machine's shells | EVERY mvnw invocation needs `$env:JAVA_HOME='C:\Program Files\Java\jdk-25'` first |
| mvnw must run from `backend/` — NOT repo root | `.\mvnw.cmd` resolves `.mvn/wrapper` relative to CWD. From root it downloads Maven and dies with ClassNotFoundException |
| `rg` (ripgrep) is NOT installed | Use `Select-String` in PowerShell. Grep tool with plain patterns works in the agent harness |
| Nested `powershell` (5.1) mangles UTF-8 in .ps1 (em-dashes → parse errors) | Run scripts with `& 'C:\Program Files\PowerShell\7\pwsh.exe' -NoProfile -ExecutionPolicy Bypass -File tools/smoke.ps1` |
| `Invoke-WebRequest` returns BYTE STREAMS for some content (health check prints numbers) | Match with `-match '"UP"'` on `$r.Content` (works despite stream), or use `Invoke-RestMethod` |
| Docker Desktop is often not running at session start | `Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"` then poll `docker version` until it answers |
| npm build can exceed 5-min default tool timeout on first run (rolldown plugin timings) | Use a ≥600000ms timeout |
| The tracing collector (HA compose, profile `tracing`) starts on network `flashreserve-ha_default`, the main app on `flashreserve_default` | `docker network connect flashreserve_default flashreserve-otel-collector` then `docker compose restart app` — DNS resolves at container start, so restart is REQUIRED after connecting |
| `.env` is a LIVE file (gitignored); edits to it are not in git | Don't "fix" .env in commits; it's machine-local |

## 1. Architecture facts that are TRUE BY DESIGN (never "fix" these)

- **Postgres is the ONLY inventory authority.** Redis availability hints
  are cache-only (3s TTL, fail-open). Making Redis authoritative IS the bug.
- **Idempotency claim** uses `INSERT ... ON CONFLICT DO NOTHING` rowcount
  semantics — deliberately NO exception signaling, because the old
  try/catch approach caused the UnexpectedRollbackException bug (§2.1).
- **Outbox: ALL broker I/O outside DB transactions.** Any code path that
  opens a transaction around a Kafka send reintroduces the
  pool-exhaustion bug (§2.2). The claim SQL is
  `FOR UPDATE SKIP LOCKED` + `leased_until` predicate IN SQL — do not move
  lease filtering back into Java (starvation bug, §2.5).
- **EventConsumers counts attempts in `consumer_retry` (V6)** — durable by
  design; the old in-memory map was the bug (§2.4).
- **Webhook signatures are versioned** `t=<unix>,v1=HMAC(secret, t "." raw)`
  with a replay window — legacy bare-base64 format is REFUSED on purpose.
- **Login limiter FAILS OPEN** when Redis is down (availability of login >
  strictness of a counter). Same for RedisRateLimiter. Deliberate.
- **`@Profile("span-probe")` was dead code** → replaced by
  `@ConditionalOnProperty(flashreserve.span-probe.enabled)`. Dormant by
  default is the point.
- **Consumers exclude Redis in most ITs by design** (fail-open paths
  don't need it); only RedisRateLimitCacheIT boots real Redis.
- **KafkaLoopIT binds host port 19092** — random mapped ports caused
  silent hops to leftover compose brokers (cross-broker leakage, flaky
  passes). Don't switch it back to random ports.

## 2. THE BUG REGISTRY — every bug ever found here, its cause, its fix

### 2.1 Concurrent idempotency claims → HTTP 500 (THE flagship bug)
- **Symptom:** 20 duplicate requests under k6 → 500s at commit,
  `UnexpectedRollbackException`. Sequential tests never saw it.
- **Cause:** `DataIntegrityViolationException` during Hibernate flush
  marks the REQUIRES_NEW tx rollback-only BEFORE the catch runs → commit
  fails.
- **Fix:** `ON CONFLICT DO NOTHING` rowcount semantics — no exceptions in
  the claim path at all.
- **Pinned by:** `ConcurrentIdempotencyClaimIT` (16 threads → 1 Fresh, 15
  InFlight, 0 exceptions). DO NOT reintroduce exception-based claiming.

### 2.2 Outbox publisher held a DB tx across up to 50 blocking Kafka sends
- **Symptom:** Kafka outage → connection pool exhausted → whole API down.
- **Fix:** Short claim tx → send with NO tx → per-row short tx for state
  flip. Restructured again in the second pass: claim = SKIP LOCKED +
  lease; failure flip + backoff lease in ONE tx.

### 2.3 Admin N+1 / unbounded expiry scan
- Found by benchmarks; fixed via batch queries + bounded scans
  (`EXPIRATION_BATCH_SIZE`). Don't loop per-row.

### 2.4 Consumer retry counters were in-memory (bounded per uptime)
- **Fix:** V6 `consumer_retry` table; cleared on success/dead-letter;
  retention purge. "Bounded retry" is now bounded GLOBALLY.

### 2.5 Outbox claim query LIED about SKIP LOCKED + lease starvation
- **Found in this session's audit:** javadoc claimed FOR UPDATE SKIP
  LOCKED but the SQL had neither; lease filtering happened in Java
  AFTER LIMIT → a batch of 50 leased rows starves the poll into
  returning nothing while work exists.
- **Fix:** lease predicate moved INTO the SQL claim:
  `WHERE state IN (...) AND (leased_until IS NULL OR leased_until <=
  now()) ... FOR UPDATE SKIP LOCKED`. Post-filter deleted.
- DO NOT re-add Java-side lease filtering "for clarity".

### 2.6 Failure-path double transaction (unleased window)
- **Fix:** markFailed + backoff lease commit atomically in one short tx.
  markFailed no longer clears the lease itself.

### 2.7 Login response hardcoded `expiresIn: 3600`
- **Cause:** AuthController ignored `flashreserve.jwt.ttl-seconds`; k6
  helpers cache tokens off expiresIn → a shortened TTL would break long
  benchmarks mid-flight.
- **Fix:** inject the configured TTL and return it.

### 2.8 SpanProbeController unreachable (dead endpoint)
- Was `@Profile("span-probe")` — nothing activates that profile.
- **Fix:** `@ConditionalOnProperty("flashreserve.span-probe.enabled")`,
  JWT-protected, documented, exercised in the live tracing drill.

### 2.9 OTLP METRICS registry noise-POSTing localhost:4318 every 60s
- **Symptom:** `spring-boot-starter-opentelemetry` drags in the OTLP
  metrics registry; default URL localhost:4318; ConnectException spam in
  every environment that has no collector.
- **Fix:** `management.otlp.metrics.export.enabled: ${OTEL_METRICS_EXPORT_ENABLED:false}`.
  An empty-URL property does NOT stop it — the enabled flag does.

### 2.10 `@ConditionalOnProperty` vs blank endpoint (tracing exporter)
- **Trap:** `@ConditionalOnProperty` on `management.otlp.tracing.endpoint`
  treats EMPTY STRING as present → builds exporter with blank endpoint →
  "Invalid endpoint" at boot. That's why TracingExportConfig uses a custom
  `Condition` reading the raw property value. Don't "simplify" it.

### 2.11 Stale/duplicate/dead references left by the second pass (all removed)
- Dead repo methods `findByStateAndLeasedUntilBefore`, dead
  `isLeaseActive`, dead `lastConsistentRef` field, unused
  `Transactional`/`OtlpHttpSpanExporterBuilder` imports, unused
  `transport` param in the exporter bean. If you're re-adding any of
  these, you're regressing, not improving.

### 2.12 smoke.ps1 suspension step sent no Content-Type → structured 400
- **Symptom:** step 14 failed with `Unsupported Content-Type` — the API
  was CORRECT (the new GlobalExceptionHandler mapping); the drill was wrong.
- **Fix:** `$adminJson = $admin + $ct` on state-change POSTs.
- **Lesson:** when a smoke step fails, FIRST decide whether the API or
  the test is wrong before touching code. The 400-for-form-encoded is a
  FEATURE (pinned by AuthHardeningIT).

### 2.13 PowerShell try/catch swallows `throw` inside try (lockout loop)
- `try { Post ...; throw 'unexpected' } catch { ... }` — the internal
  throw is caught by the SAME catch. Fixed pattern: do the call in try,
  extract `$code` in catch, assert OUTSIDE the try/catch.

### 2.14 .env rewrite pipeline ordering bug (this session)
- `(Get-Content .env) + @('KEY=VAL') | Where-Object { -notmatch 'KEY=' }`
  filtered the APPENDED lines too. Correct pattern: filter FIRST, append
  AFTER. (Broken version briefly produced duplicate/blank OTEL keys.)

## 3. Failure modes that LOOK like bugs but are correct behavior

| Observation | Verdict |
|---|---|
| `POST /api/reservations` with form-encoded body → 400 `Unsupported Content-Type` | CORRECT — HttpMediaTypeNotSupportedException maps to structured 400 (pinned) |
| Login succeeds right after 5 failed attempts in ITs (no Redis there) | CORRECT — limiter fails open; lockout is exercised against live Redis in smoke + RedisRateLimitCacheIT |
| 16 threads on one idempotency key → 15 "in-flight" 409/InFlight responses | CORRECT — exactly 1 wins |
| Outbox rows stay PENDING briefly after commit | CORRECT — poll-based drain, ≤500ms |
| `flashreserve_reservation_outcome{outcome="error"}` increments | Check the cause — it counts DomainException failures like INVENTORY_UNAVAILABLE too? No — those are `inventory_unavailable`. If you see error counts, grep app logs for the actual exception |
| KafkaLoopIT takes ~60s | NORMAL — drains the shared-container backlog through a real broker |
| Health endpoint prints numbers as separate lines | PowerShell byte-stream rendering artifact; use Invoke-RestMethod |

## 4. The outbox lease model (memorize before touching outbox code)

1. Claim tx: `findPublishable(50)` = SKIP LOCKED over
   PENDING/FAILED + lease-free/expired rows; each row leased +60s (dirty
   flush inside the same tx).
2. Send: NO transaction. `kafkaTemplate.send(...).get()` (blocking).
3. Success: short tx → `markPublished` (clears lease).
4. Failure: ONE short tx → `markFailed` (retry_count++) + re-lease
   `2^retry` seconds capped 60 (exponential backoff). Dead after
   `maxRetries` (default 10).
5. Crashed publisher: lease expires naturally after 60s; row re-enters
   the pool. Consumer dedup is the FINAL correctness guarantee — the
   lease only prevents wasted duplicate sends between replicas.

## 5. Verification commands (run these; don't invent new ones)

```powershell
# Backend (87/87) — from backend/ with JAVA_HOME set
$env:JAVA_HOME='C:\Program Files\Java\jdk-25'
.\mvnw.cmd verify        # ~2.5 min with Testcontainers

# Frontend (6/6 + build) — from frontend/
npm test
npm run build

# Live stack + 15-step drill — from repo root, Docker running
docker compose up -d --build
# wait for health UP, then:
& 'C:\Program Files\PowerShell\7\pwsh.exe' -NoProfile -ExecutionPolicy Bypass -File tools/smoke.ps1
# EXPECTED last line: '=== SMOKE PASSED: ... ===' with 15 OK lines above

# Tracing drill (optional, live)
docker compose -f docker-compose.ha.yml --profile tracing up -d otel-collector
docker network connect flashreserve_default flashreserve-otel-collector
# .env: OTEL_EXPORTER_OTLP_ENDPOINT=http://flashreserve-otel-collector:4318
#       SPAN_PROBE_ENABLED=true
docker compose up -d --force-recreate app
# login, POST /api/dev/span-probe with Bearer; then:
docker logs flashreserve-otel-collector --since 3m   # must show flashreserve.span-probe spans
```

**Pass state as of 2026-09-07:** backend 87/87 · frontend 6/6 + build
clean · smoke drill 15/15 · tracing drill verified live · outbox drained
838 events live · reconciliation consistent, 0 findings.

## 6. If you must change something anyway

1. Read the relevant class + its test FIRST. The test names the contract.
2. State the exact line and mechanism you're changing and why.
3. Run §5's commands after. Green = done. Red = revert.
4. Do NOT widen scopes, rename things, "modernize", or touch working files
   adjacent to your change.
5. If the fix isn't obvious within one attempt, STOP — you're probably
   fighting something documented in §0–§4. Read again.
