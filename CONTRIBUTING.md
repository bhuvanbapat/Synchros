# Contributing

## Ground rules

- **Correctness first.** Every mutation of inventory, reservation, order,
  or payment state must be idempotent and race-safe. If your change
  touches a transaction boundary, read docs/CONCURRENCY.md and the
  relevant ADR before writing code.
- **The database is the authority.** No new code path may treat Redis or
  Kafka as a source of truth for inventory ownership.

## Setup

```bash
docker compose up -d postgres redis kafka
cd backend && ./mvnw spring-boot:run     # or mvnw.cmd on Windows
cd frontend && npm install && npm run dev
```

## Before you commit

```bash
# backend (all 87 tests; requires Docker for Testcontainers)
cd backend && ./mvnw test

# frontend
cd frontend && npm run lint && npm test -- --run && npm run build
```

Both must be green. CI runs the same commands.

## Test-writing expectations

- A concurrency bug fix is not done until there is a **concurrent**
  regression test that fails on the old code. Sequential tests of races
  prove nothing (ADR-009).
- Integration tests share one Postgres container: seed unique
  sections/UUIDs and leave no global state that breaks reconciliation
  assertions for other tests (expire/cancel what you hold).
- Load-test changes: business counters (successes ≤ units) are the exit
  criteria, never k6's protocol metrics.

## Style

- Java: constructor injection, no field `@Autowired`, no Lombok; entities
  keep guarded state transitions in the entity.
- SQL: schema changes are new Flyway migrations only — never edit an
  applied migration (checksums are frozen).
- Logs: single-line structured events with IDs; never log secrets or raw
  payment payloads.
- New config: goes through `SynchrosProperties` with an env override
  documented in `.env.example`.

## Commits

Message format: `area: imperative summary` — e.g.
`reservation: lock row before order-create state checks`. For multi-what
changes, a body with bullets is fine; explain *why*, not *what* (the diff
knows).
