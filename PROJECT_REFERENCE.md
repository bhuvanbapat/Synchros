# FlashReserve — Complete Project Reference

Ultra-detailed inventory of everything in this repository. Every claim
here is verifiable against the code it describes.

**Project type:** high-concurrency reservation platform (limited-release
event ticketing demo domain)
**Core engineering question:** *500 users race 100 units — how do you
guarantee no oversell, no duplicate orders, no lost events?*

---

## 1. Repository layout

```
FlashReserve/
├── backend/                          Spring Boot 4.1.1, Java 25
│   ├── mvnw, mvnw.cmd                 Maven wrapper (unix + windows, LF-safe)
│   ├── .mvn/wrapper/                 wrapper jar + distribution properties (Maven 3.9.12)
│   ├── pom.xml                        dependencies (see §3)
│   ├── Dockerfile                     multi-stage build -> runtime image
│   └── src/
│       ├── main/java/com/flashreserve/    21 packages, 81 files (§4)
│       ├── main/resources/
│       │   ├── application.yml            all config, env-overridable (§6)
│       │   └── db/migration/              Flyway V1–V5 (§5)
│       ├── test/java/com/flashreserve/    16 test files (§7)
│       └── test/resources/application-test.yml
├── frontend/                         React 19 + TypeScript + Vite 8
│   ├── src/
│   │   ├── App.tsx            shop + admin dashboards, full user flow
│   │   ├── HoldCountdown.tsx  server-authoritative TTL countdown
│   │   ├── api.ts             typed API client, structured ApiError
│   │   ├── types.ts           API DTO types
│   │   └── App.test.tsx       5 vitest component tests
│   └── (vite.config.ts proxies /api -> :8081 in dev)
├── load-tests/                       k6 scenarios (§8)
│   ├── flash-sale.js
│   ├── duplicate-request.js
│   ├── steady-traffic.js
│   └── helpers.js
├── docs/                             11 deep docs + 10 ADRs (§9)
├── .github/workflows/ci.yml          CI (§10)
├── docker-compose.yml                postgres + redis + kafka + app
├── .env.example                      every tunable, safe defaults
├── BUILD_STATUS.md                   completion checklist
├── CHANGELOG.md · CONTRIBUTING.md · LICENSE (MIT) · README.md
└── .gitignore · .gitattributes       no secrets/binaries; LF-safe scripts
```

---

## 2. Domain model

### Entities (all persisted in PostgreSQL, Flyway V1/V3)

| Entity | Table | Purpose |
|---|---|---|
| `User` | `fr_user` | email + bcrypt hash + role (USER/ADMIN) + account_state (ACTIVE/SUSPENDED) |
| `Event` | `fr_event` | catalog entry (name, starts_at, state) — demo: "Neon Pulse Live" |
| `Venue` | `venue` | minimal catalog parent |
| `InventoryPool` | `inventory_pool` | **the authoritative counter**: per (event, section) `total` + `available` with CHECK `0 ≤ available ≤ total`, `@Version` |
| `Reservation` | `reservation` | a hold: state machine, quantity, section, hold_expires_at, `@Version` |
| `Order` | `fr_order` | one per reservation (UNIQUE), state machine, amount_cents, `@Version` |
| `Payment` | `payment` | one INITIATED payment per order; terminal SUCCEEDED/FAILED/TIMED_OUT |
| `PaymentAttempt` | `payment_attempt` | append-only record of every gateway outcome incl. DUPLICATE_CALLBACK, LATE_CALLBACK |
| `IdempotencyKey` | `idempotency_key` | (user, operation, key) claim + request_hash + cached response (JSONB) + 24h TTL |
| `OutboxEvent` | `outbox_event` | event row committed with the business mutation; PENDING→PUBLISHED/FAILED/DEAD |
| `ProcessedEvent` | `processed_event` | consumer dedup marker, UNIQUE (event_id, consumer_group) |
| `DeadLetter` | `dead_letter` | quarantined poison messages with error + attempt count |
| `AuditEvent` | `audit_event` | append-only trail: actor, operation, entity, result, requestId, JSONB details |
| `Notification` | `notification` | user-facing projection produced by Kafka consumer |
| `AnalyticsEvent` | `analytics_event` | raw event feed projection (RESERVATION/ORDER/PAYMENT categories) |

### State machines (guarded in entity, mirrored by DB CHECK)

```
Reservation:  HELD ──confirm──▶ CONFIRMED   (terminal)
              HELD ──expire───▶ EXPIRED     (terminal)
              HELD ──cancel──▶ CANCELLED    (terminal)
              (FAILED reserved; terminal states never re-enter)

Order:        PENDING_PAYMENT ──success────▶ CONFIRMED (terminal)
              PENDING_PAYMENT ──failure────▶ FAILED    (terminal)
              PENDING_PAYMENT ──hold lapse─▶ EXPIRED   (terminal)
              PENDING_PAYMENT ──cancel────▶ CANCELLED (terminal)

Payment:      INITIATED ──▶ SUCCEEDED | FAILED | TIMED_OUT
              (TIMEOUT outcome deliberately leaves INITIATED: retryable)
```

`transitionTo()` throws on illegal edges; the throw aborts the surrounding
transaction, rolling back any paired inventory mutation. Duplicate
confirm = idempotent no-op. Illegal confirm (lost expiry race) = domain
409, never 500.

---

## 3. Backend technology stack (every dependency earns its place)

| Dependency | Why it exists |
|---|---|
| spring-boot-starter-web | REST API (Tomcat) |
| spring-boot-starter-data-jpa | persistence (Hibernate 7) |
| spring-boot-starter-validation | Bean Validation on all DTOs |
| spring-boot-starter-security | Basic auth, roles, filter chain, CORS |
| spring-boot-starter-actuator | health/info/metrics/prometheus endpoints |
| spring-boot-starter-data-redis | availability-hint cache + rate-limit bucket store |
| spring-boot-starter-kafka | outbox publisher + listeners |
| micrometer-registry-prometheus | `/actuator/prometheus` scrape target |
| flyway-core + flyway-database-postgresql + spring-boot-flyway | explicit V1–V5 migrations; `ddl-auto: validate` |
| postgresql (JDBC) | the database |
| test: spring-boot-starter-test, spring-security-test | test harness |
| test: testcontainers (junit-jupiter, postgresql, kafka), spring-boot-testcontainers | real-Postgres ITs |

No Lombok, no extraneous starters — 81 main source files, constructor
injection throughout.

---

## 4. Backend packages (modular monolith boundaries)

| Package | Contents | Responsibility |
|---|---|---|
| `catalog` | Event, Venue-backed EventRepository, CatalogService, EventController, EventDto | event listing/detail; never touches inventory |
| `inventory` | InventoryPool, InventoryPoolRepository, InventoryController | **oversell-critical**: `decrementAvailable` = atomic conditional UPDATE (`WHERE available >= :qty`); `incrementAvailable` for releases; UUID-only lookups |
| `reservation` | Reservation, ReservationService, ReservationController, ReservationRepository, ReservationLockRepository, ReservationExpirationJob, ReservationDtos | hold creation (single tx: decrement + INSERT + outbox + audit), confirm/cancel (row-locked), TTL expiry job (conditional flip + release), ownership enforcement |
| `order` | Order, OrderService, OrderController, OrderRepository, OrderLockRepository | order creation (reservation row lock + UNIQUE per reservation), payment application (order row lock, three-outcome switch) |
| `payment` | MockPaymentGateway (weighted SUCCESS/FAILURE/TIMEOUT), PaymentRelay, PaymentWebhookController, PaymentWebhookService, Payment, PaymentAttempt, repositories | deterministic gateway; relay delivers through the real webhook path so idempotency is exercised |
| `idempotency` | IdempotencyService, IdempotencyKey, IdempotencyKeyRepository | (user, operation, key) claims via `INSERT .. ON CONFLICT DO NOTHING` (rowcount semantics — never exception-based, see ADR-007), SHA-256 fingerprints, cached replays, 24h TTL purge |
| `outbox` | OutboxService (MANDATORY propagation — must join the business tx), OutboxEvent, OutboxRepository, OutboxPublisher, FlashReserveTopics | event rows commit with mutations; publisher drains with **zero broker I/O inside any transaction** (short read/mark txs via TransactionTemplate); bounded retries → DEAD |
| `kafka` | EventConsumers (listeners), EventHandlers (transactional dedup + projections), ProcessedEvent, DeadLetterService, DeadLetter, ConditionalOnKafkaEnabled | at-least-once delivery, exactly-once effects via processed_event marker; poison quarantine after 5 attempts; flag-gated for hermetic tests |
| `cache` | CatalogCache | Redis availability hints, 3s TTL, fail-open, hit/miss/error metrics |
| `ratelimit` | RateLimiter, RedisRateLimiter | atomic Lua token bucket per (endpoint, user); fails open; allowed/rejected metrics |
| `reconciliation` | ReconciliationService | REPEATABLE_READ report: `total = available + Σheld + Σsold`, stuck-expired holds, orphaned pending orders |
| `notification` | Notification, NotificationService, NotificationRepository | user inbox projection |
| `analytics` | AnalyticsEvent, AnalyticsService, AnalyticsRepository | event-feed projection + rollups for admin |
| `audit` | AuditEvent, AuditService, AuditRepository | append-only trail; entries commit with the mutation (REQUIRED); requestId captured from MDC |
| `security` | SecurityConfig, FlashUserDetails(+Service), CurrentUserArgumentResolver, UnauthorizedException | Basic auth entry point, structured 401/403 JSON, ROLE_ADMIN gates on `/api/admin/**` + `/actuator/**`, CORS allow-list, suspended-account rejection, resolver for `@CurrentUser` |
| `user` | User, UserService, AuthController, UserController, AuthDtos, UserRepository | register (race-safe: UNIQUE collision → clean 400), login validation, /me endpoints |
| `common` | DomainException (+ 12 stable error codes), GlobalExceptionHandler, NotFoundException, RequestIdFilter | `{code, message, requestId, timestamp}` error model; X-Request-Id propagation into MDC |
| `config` | FlashReserveProperties, PropertiesConfig, WebMvcConfig | typed, env-overridable configuration |
| `metrics` | FlashMetrics | business counters/timers (see §11) |
| `health` | HealthController | app-level DB + Redis checks with pooled-connection hygiene |
| `admin` | AdminController | ops API: metrics (grouped queries), outbox failed/dead, dead letters, audit, analytics, reconciliation, idempotency purge |
| root | FlashReserveApplication | `@SpringBootApplication` + `@EnableScheduling` |

---

## 5. Database (Flyway V1–V5, ~250 lines of SQL)

| Migration | Contents |
|---|---|
| V1 core_schema | all tables, CHECK constraints (availability bounds, enum states, positive quantities), partial indexes on hot paths (`hold_expires_at WHERE state='HELD'`, outbox `created_at WHERE state IN (PENDING,FAILED)`), UNIQUE keys that do real work (idempotency scope, order-per-reservation, consumer dedup) |
| V2 seed_demo | venue "Neon Dome Arena", users (alice/bob/admin, bcrypt `password`), event "Neon Pulse Live", pools FLOOR 10 + BALCONY 500 |
| V3 analytics | analytics_event projection table + index |
| V4 payment_attempt_outcome | extend outcome CHECK with LATE_CALLBACK |
| V5 drop_unused_inventory_item | remove the never-referenced unit-level table (dead schema = dead code) |

`ddl-auto: validate` — Hibernate never mutates schema. V1–V4 checksums
frozen; changes only ever as new migrations.

---

## 6. Configuration (application.yml + .env.example, 1:1)

| Knob | Default | Effect |
|---|---|---|
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | localhost:5432/flashreserve | DB connection |
| `REDIS_HOST/PORT` | localhost:6379 | cache + rate limit |
| `KAFKA_BOOTSTRAP_SERVERS` | localhost:9092 | broker |
| `SERVER_PORT` | 8081 | API (8080 avoided: commonly claimed by k8s tooling) |
| `HOLD_DURATION_SECONDS` | 120 | reservation TTL |
| `EXPIRATION_SCAN_INTERVAL_MS / BATCH_SIZE` | 1000 / 100 | expiry job cadence + bounded scan |
| `RATE_LIMIT_RESERVATIONS_PER_MINUTE / BURST` | 30 / 10 | token bucket per user |
| `OUTBOX_POLL_INTERVAL_MS / MAX_RETRIES` | 500 / 10 | publisher drain + retry bound |
| `PAYMENT_SUCCESS/FAILURE/TIMEOUT_WEIGHT` | 0.85/0.10/0.05 | mock gateway distribution |
| `FLASHRESERVE_SCHEDULING_ENABLED` | true | jobs on/off (tests: off) |
| `flashreserve.kafka.enabled` | true | listeners + publisher on/off (tests: off) |
| `FRONTEND_ORIGIN` | http://localhost:5173 | CORS allow-list entry |

Tomcat threads 200 / Hikari pool 40 (tuned for the 500-VU benchmark).
Kafka producer: `acks=all` + `enable.idempotence=true`.

---

## 7. Test inventory (16 files — 45 backend + 5 frontend tests)

| Test | What it proves |
|---|---|
| `ReservationStateMachineTest` | legal/illegal transition edges |
| `FingerprintTest` | idempotency hashing + canonical serialization |
| `OversellPreventionIT` | **100 clients vs 10 units → ≤10 succeed; available ≥ 0**; repeated 3× with different sizes |
| `ExpirationIT` | expiry releases inventory; confirm-vs-expire race → exactly one winner; confirm-after-expiry → clean failure |
| `OrderCreateExpirationRaceIT` | create-vs-expire race, 20 rounds → 0 illegal outcomes; 8-way concurrent order creation → 1 order row |
| `PaymentFlowIT` | success/failure/duplicate paths; 8 concurrent duplicate callbacks → 1 effect; late callback after expiry absorbed; ownership enforced |
| `PaymentRetryIT` | **timeout no longer bricks retries**: TIMEOUT→SUCCESS confirms; repeated timeouts stay retryable; 8-way duplicate-email registration → 1 user + 7 clean 400s |
| `IdempotencyIT` | replay / conflict / different-keys / in-flight semantics |
| `ConcurrentIdempotencyClaimIT` | **16 threads racing one key → 1 Fresh, 15 InFlight, 0 exceptions** (the regression test for the ON CONFLICT fix) |
| `ApiIdempotencyIT` | end-to-end over HTTP: replay returns the original reservation with `X-Idempotent-Replay: true`; same key + different body → 409 |
| `ApiSecurityIT` | 401s, wrong password, admin gate (user 403 / admin 200), IDOR blocked, malformed body 400, negative quantity 400, SQL-injection fails safely |
| `OutboxIT` | outbox row commits with the mutation; rollback removes both |
| `ReconciliationIT` | clean world consistent; manually corrupted counter detected; stuck hold detected and cleared by real expiry |
| `EventHandlersTest` | consumer dedup suppresses duplicates before any effect; concurrent marker insert absorbed; order/payment project to analytics only |
| `DeadLetterServiceTest` | poison payloads (even unparseable) persist with error + attempts |
| `App.test.tsx` (frontend) | inventory rendering, sold-out state, hold panel + countdown, INVENTORY_UNAVAILABLE error surface, checkout outcome |

Shared Postgres container for suite speed; tests seed isolated sections
and clean up terminal state so global reconciliation assertions hold.

---

## 8. Load tests (k6 v1.4, scripts in `load-tests/`)

| Script | Scenario | Measured result (this machine) |
|---|---|---|
| `flash-sale.js` | N fresh users (parallel-batch registration) race one pool; expected statuses 201/409/429 declared | 500 VUs vs 100 units → **exactly 100 successes, 400 clean 409s, 0 5xx**, final availability exactly 0 |
| `duplicate-request.js` | 20 VUs, ONE run-scoped key (env `RUN_TAG`), concurrent | **exactly 1 logical reservation**; 40/40 checks |
| `steady-traffic.js` | ramp 2→20 VUs, 80% reads / 20% reserves | ~15.7 req/s; p50 371ms, p95 1.39s (honestly recorded vs the 500ms target, with cause) |
| `helpers.js` | Basic-auth builder (no btoa in k6), business counters | — |

These benchmarks found two shipped bugs during the audit (idempotency
claim 500s; per-VU key generation) — that yield is the point (ADR-010).

Note: on OneDrive/URL-encoded paths, k6 on Windows fails to resolve
relative imports — copy the scripts to a plain path to run.

---

## 9. Documentation set

| Document | Contents |
|---|---|
| `docs/ARCHITECTURE.md` | system diagram, consistency-boundary table, event flow, failure-mode table, module boundaries, deployment |
| `docs/CONCURRENCY.md` | the oversell problem, the chosen CAS mechanism with SQL, why each alternative was rejected, per-operation isolation table, remaining races + closures, measured proof |
| `docs/DATABASE.md` | full schema, every index with its justifying query, CHECK invariants, transaction boundaries, migration history |
| `docs/EVENTS.md` | envelope format, producer path + guarantees, consumer dedup, projections, DLQ, failure semantics |
| `docs/IDEMPOTENCY.md` | scope, fingerprints, the ON CONFLICT claim protocol (and why exception-based claiming was a bug), completion/release, idempotency-vs-rate-limit distinction, verified behavior matrix |
| `docs/SECURITY.md` | authn/z, anti-IDOR design, injection safety, webhook hardening, threat→control matrix (with test names), stated limitations |
| `docs/OBSERVABILITY.md` | request correlation, structured-log conventions, every business metric, health endpoints, tracing decision |
| `docs/PERFORMANCE.md` | environment, three scenarios with measured numbers (including misses), exposed bottlenecks + fixes, reproduction commands |
| `docs/DEMO.md` | step-by-step scripted walkthrough (all commands PowerShell-executable) |
| `docs/RESUME_NOTES.md` | description, stack, hardest problems, mechanisms, measured results, 3 paste-ready resume bullets, limitations |
| `docs/INTERVIEW_GUIDE.md` | 18 deep Q&As matching this implementation exactly |
| `docs/adr/ADR-001…010` | modular monolith · Postgres authority · concurrency strategy · Redis scope · Kafka architecture · transactional outbox · idempotency model · state machines · testing strategy · load-testing strategy |

---

## 10. CI (`.github/workflows/ci.yml`)

Two jobs, free-runner compatible:
- **backend** (ubuntu, Temurin 25, maven cache): `chmod +x mvnw` → `./mvnw test` (real Testcontainers suite) → `./mvnw -DskipTests package`; surefire reports uploaded on failure.
- **frontend** (Node 24, npm cache): `npm ci` → `oxlint src` → `npm test -- --run --pool=threads` → `npm run build`.

Both steps were executed locally from scratch during the final audit
(45/45, package SUCCESS, lint 0, 5/5, build green). The unix `mvnw`
wrapper is committed with mode 100755 and LF-enforced via `.gitattributes`.

---

## 11. Operational API surface

| Endpoint | Auth | Purpose |
|---|---|---|
| `POST /api/auth/register` · `POST /api/auth/login` | public | account creation (race-safe), credential validation |
| `GET /api/events` · `GET /api/events/{id}` | user | catalog |
| `GET /api/inventory?eventId=` · `GET /api/inventory/{uuid}` | user | availability hints (3s cache) |
| `POST /api/reservations` (+`Idempotency-Key`) | user, rate-limited | create hold; replay/conflict semantics |
| `GET /api/reservations/{id}` · `GET /api/reservations` | owner | authoritative hold state / my holds |
| `POST /api/reservations/{id}/cancel` | owner | release held units |
| `POST /api/orders` · `GET /api/orders...` | owner | checkout (idempotent per reservation) |
| `POST /api/orders/{id}/pay` | owner | mock charge relayed through the webhook path |
| `POST /api/payment-webhooks` | public (PSP) | idempotent outcome application |
| `GET /api/users/me` · `/me/notifications` | user | profile + Kafka-consumer inbox |
| `GET /api/health` | user | DB + Redis status |
| `GET /actuator/health,info` | public | liveness/readiness |
| `GET /actuator/metrics,prometheus` | admin | metrics scrape |
| `/api/admin/metrics · /outbox/failed · /outbox/dead · /dead-letters · /audit · /analytics` | admin | live operational data |
| `POST /api/admin/reconciliation` | admin | consistency report |
| `POST /api/admin/maintenance/purge-expired-idempotency` | admin | TTL purge |

Error model everywhere: `{code, message, requestId, timestamp}` —
12 stable domain codes (INVENTORY_UNAVAILABLE, RESERVATION_EXPIRED,
IDEMPOTENCY_CONFLICT, RATE_LIMIT_EXCEEDED, FORBIDDEN, …) mapped to
correct HTTP statuses; 401/403 from the security layer use the same shape.

---

## 12. Frontend behavior

- **Login** validates against the real API before entering.
- **Shop**: events → per-section availability (sold-out states disabled) →
  reserve (idempotency key per click-intent) → hold panel with
  server-authoritative countdown (refetches on timer zero — backend
  decides, never the browser) → simulated pay → outcome badge →
  notifications inbox.
- **Admin** (visible for admin@): live-refreshing pools (total/available/
  held/sold), reservation counts by state, outbox by state, dead letters,
  reconciliation button with result line.
- Production build uses relative API paths (dev proxy in vite config);
  `VITE_API_BASE` overrides for other deployments.

---

## 13. Verified-measurement ledger (nothing fabricated)

| Claim | Where proven |
|---|---|
| 500 clients → exactly 100 units, 0 oversell, 0 5xx | live k6 run + OversellPreventionIT |
| 20 concurrent duplicates → 1 reservation | live k6 + DB count before/after |
| 16-thread idempotency race → 0 exceptions | ConcurrentIdempotencyClaimIT |
| Kafka down: commits continue, outbox buffers, drains to 0 on recovery | live outage drill (754→759 published, 0 lost) |
| Hold expiry restores inventory; confirm races collapse to one winner | live cycle + ExpirationIT + OrderCreateExpirationRaceIT |
| available + held + sold = total for every pool | SQL-verified post-benchmark + reconciliation endpoint |
| Outbox: 700+ events published, 0 failed, 0 dead across all runs | admin metrics |
| Reconciliation consistent, 0 findings after every chaos drill | admin endpoint |

## 14. Known limitations (deliberate, documented)

HTTP Basic + external TLS (portfolio scope; ownership checks are
auth-agnostic) · webhook HMAC documented, not simulated · single
broker/DB instance (HA topology in ADRs) · single-machine benchmark
numbers (invariants are the portable part) · OpenTelemetry deferred in
favor of correlation IDs · consumer retry counters in-memory.
