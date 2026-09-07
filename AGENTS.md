# FlashReserve — Agent Instructions

**Before doing ANYTHING in this repository, read
[docs/SESSION_HANDOFF.md](docs/SESSION_HANDOFF.md).** It is the complete,
authoritative registry of:

1. Environment gotchas (JAVA_HOME, mvnw CWD, pwsh vs powershell, Docker
   startup, npm timeouts, compose network bridging).
2. Every bug ever found here, its root cause, and its fix — plus the
   regression test that pins each one.
3. Design invariants that are TRUE BY DESIGN and must never be
   "improved" (Postgres-only inventory authority, exception-free
   idempotency claims, outbox broker-I/O-outside-transactions, SKIP
   LOCKED + lease claiming, versioned webhook signatures, fail-open
   Redis limiter, durable consumer retry counters).
4. The exact verification commands and their expected outputs.

## Rules of engagement

- **Do not re-diagnose anything already documented as fixed.** If your
  problem matches the SESSION_HANDOFF registry, apply the documented
  resolution or stop.
- **Do not modify code unless you can state the exact file, line, and
  mechanism you are changing and why.** If you cannot, run the
  verification commands instead — the project is green.
- **Never break a pinned regression test.** Each one exists because a
  real bug was found by it. A "cleanup" that breaks a pin is a
  regression, not a refactor.
- **No scope widening.** Fix the named issue; touch nothing adjacent.
- **Verification is mandatory after any change:** backend `mvnw verify`
  (87/87) from `backend/` with JAVA_HOME set; frontend `npm test` +
  `npm run build` from `frontend/`; live stack via `docker compose up
  -d --build` then `tools/smoke.ps1` under pwsh 7 (15/15 steps).
- Docs live in `docs/` with ADRs in `docs/adr/`; measurement claims in
  docs must come from actual runs on this machine — never invented.
  Update BUILD_STATUS.md, CHANGELOG.md, and LIMITATIONS.md when the
  set of true things changes.
