# FlashReserve — High-Concurrency Reservation & Inventory Platform

## What this is

FlashReserve is a backend-heavy engineering project that solves the classic
**high-demand reservation problem**:

> 100 users compete for 10 units of inventory at the same time.
> The system must never oversell, double-book, duplicate an order, or lose an event.

Primary demo domain: **limited event ticket reservation**. The architecture is
general enough to support flash-sale products, appointment slots, rooms, etc.

The centerpiece is *correctness under concurrency*, not features:

| Concern | Mechanism |
|---|---|
| Overselling prevention | PostgreSQL `UPDATE ... WHERE available >= qty` conditional writes (atomic compare-and-set) + row locks |
| Duplicate requests | Idempotency keys with request fingerprinting (DB-backed, transactional) |
| Lost events | Transactional outbox + Kafka publisher with bounded retries + dead-letter |
| Duplicate event delivery | Consumer-side idempotency via processed-event table |
| Abandoned holds | TTL expiration job with confirm/expire race protection |
| Payment callbacks | Deterministic mock gateway, idempotent webhook handling |
| Hot reads / abuse | Redis cache-aside for catalog + Redis rate limiting (never authoritative for inventory) |
| Consistency | Inventory ownership is strictly transactional; notifications/analytics are eventually consistent |
| Auditability | Append-only audit table + request correlation IDs end-to-end |
| Observability | Actuator, Micrometer/Prometheus metrics, structured logs, reconciliation |

## Quick start (Docker)

```bash
cp .env.example .env
docker compose up --build
# backend:   http://localhost:8080
# frontend:  http://localhost:5173 (dev) or served by nginx (compose)
```

Services: app (Spring Boot), PostgreSQL, Redis, Kafka (+ ZooKeeper-less KRaft or bitnami image), frontend.

## Quick start (local dev)

```bash
# 1. infra only
docker compose up -d postgres redis kafka

# 2. backend
cd backend && mvn spring-boot:run

# 3. frontend
cd frontend && npm install && npm run dev
```

## The core demo

1. Open an event with limited inventory (`curl localhost:8080/api/events`).
2. Reserve a unit → `POST /api/reservations` with `Idempotency-Key` header → status `HELD` with countdown.
3. Pay within hold TTL via mock gateway → order `CONFIRMED`, inventory `SOLD`.
4. Let a hold expire → inventory automatically returns to `AVAILABLE`.
5. Fire 100 concurrent requests at 10 units → exactly ≤ 10 succeed.
6. Replay the same reservation request 5× → one logical reservation.
7. Kill Kafka → business transactions still commit; outbox drains when it returns.

## Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — system design, boundaries, diagrams
- [docs/CONCURRENCY.md](docs/CONCURRENCY.md) — oversell prevention, lock strategy, isolation
- [docs/DATABASE.md](docs/DATABASE.md) — schema, migrations, indexes, transactions
- [docs/EVENTS.md](docs/EVENTS.md) — topics, outbox, DLQ, consumer idempotency
- [docs/IDEMPOTENCY.md](docs/IDEMPOTENCY.md) — keys, fingerprints, scope
- [docs/SECURITY.md](docs/SECURITY.md) — threat model, controls
- [docs/OBSERVABILITY.md](docs/OBSERVABILITY.md) — logs, metrics, traces
- [docs/PERFORMANCE.md](docs/PERFORMANCE.md) — measured load-test results
- [docs/DEMO.md](docs/DEMO.md) — scripted walkthrough
- [docs/RESUME_NOTES.md](docs/RESUME_NOTES.md) — resume/interview material
- [docs/INTERVIEW_GUIDE.md](docs/INTERVIEW_GUIDE.md) — deep-dive Q&A
- [docs/adr/](docs/adr/) — ADR-001…010

## Load tests

`load-tests/` contains k6 scripts (also runnable via `docker run grafana/k6`):

```bash
k6 run load-tests/flash-sale.js
k6 run load-tests/reservation-contention.js
k6 run load-tests/duplicate-request.js
```

## License

MIT — see [LICENSE](LICENSE).
