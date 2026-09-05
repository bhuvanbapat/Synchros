# FlashReserve — High-Concurrency Reservation & Inventory Platform

## What this is

FlashReserve is a backend-heavy engineering project that solves the classic
**high-demand reservation problem**:

> 500 users compete for 100 units of inventory at the same time.
> The system must never oversell, double-book, duplicate an order, or lose an event.

Primary demo domain: **limited event ticket reservation**. The architecture is
general enough to support flash-sale products, appointment slots, rooms, etc.

The centerpiece is *correctness under concurrency*, not features:

| Concern | Mechanism |
|---|---|
| Overselling prevention | PostgreSQL `UPDATE ... WHERE available >= qty` conditional writes (atomic compare-and-set) + `CHECK` constraint backstop |
| Duplicate requests | Idempotency keys claimed via `INSERT .. ON CONFLICT DO NOTHING` (exception-free), request fingerprints, cached replays |
| Lost events | Transactional outbox + Kafka publisher with bounded retries + dead-letter; **no broker I/O inside any DB transaction** |
| Duplicate event delivery | Consumer-side idempotency via `(event_id, consumer_group)` UNIQUE marker |
| Abandoned holds | TTL expiration job; confirm-vs-expire races collapse to exactly one winner (row locks + conditional flips) |
| Payment callbacks | Deterministic mock gateway; duplicate and late callbacks absorbed with zero business effect |
| Hot reads / abuse | Redis availability-hint cache (3s TTL, fail-open) + Lua token-bucket rate limiting — Redis is never authoritative |
| Consistency | Inventory ownership strictly transactional; notifications/analytics eventually consistent |
| Auditability | Append-only audit table + request correlation IDs end-to-end |
| Observability | Actuator, Micrometer/Prometheus business counters, structured logs, reconciliation |

## Verified results (measured on the dev machine — see docs/PERFORMANCE.md)

- **500 concurrent clients vs 100 units → exactly 100 successes, 400 clean
  409 rejections, 0 server errors, final availability exactly 0.**
- **20 concurrent identical requests (one idempotency key) → exactly 1
  reservation.**
- Kafka stopped → reservations still commit; outbox buffers; drains on
  recovery (0 lost events across all runs).
- Reconciliation after every benchmark: `consistent=true`, 0 findings.
- 45 backend tests + 5 frontend tests, all green.

## Quick start (Docker)

```bash
cp .env.example .env
docker compose up --build
# backend:   http://localhost:8081
```

Services: app (Spring Boot), PostgreSQL, Redis, Kafka (KRaft) — exactly the
four this project needs, nothing duplicated or decorative.

## Quick start (local dev)

```bash
# 1. infra only
docker compose up -d postgres redis kafka

# 2. backend
cd backend && mvnw.cmd spring-boot:run     # mvnw on unix

# 3. frontend
cd frontend && npm install && npm run dev   # http://localhost:5173
```

Demo accounts (password `password`): `alice@example.com`,
`bob@example.com`, `admin@flashreserve.dev` (admin).

## The core demo

1. Open an event with limited inventory (`GET /api/events`).
2. Reserve a unit → `POST /api/reservations` with an `Idempotency-Key`
   header → status `HELD` with a countdown.
3. Re-send the same request → the **same** reservation comes back
   (`X-Idempotent-Replay: true`), not a second one.
4. Pay within the hold TTL via the mock gateway → order `CONFIRMED`,
   reservation `CONFIRMED`, inventory stays sold.
5. Let a hold expire → inventory automatically returns to the pool.
6. Fire the contention benchmark → successes never exceed inventory.
7. Stop Kafka → business transactions still commit; start Kafka → the
   outbox drains.

Full walkthrough with commands: [docs/DEMO.md](docs/DEMO.md).

## Load tests

`load-tests/` contains k6 scripts. Install k6 first
(https://grafana.com/docs/k6/latest/set-up/install-k6/ — the binary is
deliberately not committed):

```bash
# get the seeded event id
$auth = @{ Authorization = 'Basic ' + [Convert]::ToBase64String(
    [Text.Encoding]::ASCII.GetBytes('alice@example.com:password')) }
$ev = (Invoke-RestMethod http://localhost:8081/api/events -Headers $auth).id

k6 run -e EVENT_ID=<uuid> -e SECTION=BALCONY -e CLIENTS=500 -e UNITS=100 load-tests/flash-sale.js
k6 run -e EVENT_ID=<uuid> -e SECTION=BALCONY -e RUN_TAG=<unique> load-tests/duplicate-request.js
k6 run -e EVENT_ID=<uuid> load-tests/steady-traffic.js
```

Note: on OneDrive/URL-encoded paths k6 v1.4.0 on Windows fails to resolve
relative imports — copy `load-tests/*.js` to a plain path (e.g.
`%TEMP%\k6run`) and run from there.

## Testing

```bash
cd backend  && mvnw.cmd test    # 45 tests; Testcontainers needs Docker
cd frontend && npm test -- --run --pool=threads
```

## Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — system design, boundaries, diagrams
- [docs/CONCURRENCY.md](docs/CONCURRENCY.md) — oversell prevention, lock strategy, isolation
- [docs/DATABASE.md](docs/DATABASE.md) — schema, migrations, indexes, transactions
- [docs/EVENTS.md](docs/EVENTS.md) — topics, outbox, DLQ, consumer idempotency
- [docs/IDEMPOTENCY.md](docs/IDEMPOTENCY.md) — keys, fingerprints, claim protocol
- [docs/SECURITY.md](docs/SECURITY.md) — threat model, controls
- [docs/OBSERVABILITY.md](docs/OBSERVABILITY.md) — logs, metrics, correlation
- [docs/PERFORMANCE.md](docs/PERFORMANCE.md) — measured load-test results
- [docs/DEMO.md](docs/DEMO.md) — scripted walkthrough
- [docs/RESUME_NOTES.md](docs/RESUME_NOTES.md) — resume/interview material
- [docs/INTERVIEW_GUIDE.md](docs/INTERVIEW_GUIDE.md) — deep-dive Q&A
- [docs/adr/](docs/adr/) — ADR-001 … ADR-010
- [BUILD_STATUS.md](BUILD_STATUS.md) — completion checklist
- [CHANGELOG.md](CHANGELOG.md) · [CONTRIBUTING.md](CONTRIBUTING.md)

## CI

GitHub Actions (`.github/workflows/ci.yml`): backend build + full test
suite (Testcontainers), frontend lint + test + build. No paid
infrastructure required.

## License

MIT — see [LICENSE](LICENSE).
