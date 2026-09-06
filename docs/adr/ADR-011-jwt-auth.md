# ADR-011: Stateless JWT bearer auth (HS256, JDK-only, fresh-principal reload)

**Status:** Accepted

## Context

The original HTTP Basic auth carried credentials on every request and
had no expiry, revocation, or capability semantics. The ownership model
(userId-scoped queries) was already auth-mechanism-agnostic, so the
upgrade only concerns the credential layer: what the client presents,
how the server verifies it, and what happens to standing.

## Decision

- **JWT bearer tokens, HS256, JDK-only implementation.** `JwtService`
  issues/verifies standard JWS compact serialization with
  `javax.crypto.Mac` — no auth library to audit, no dependency drift.
  Subject = email; custom claims carry the numeric DB user id + role so
  services never change how they scope ownership.
- **Fresh principal per request.** `JwtAuthFilter` verifies the token,
  then loads the user from the DB before setting the context. The token
  authenticates; the database authorizes. Suspension and role changes
  bind on the very next request — no revocation list needed for the
  realistic threat.
- **Verification discipline**: constant-time signature comparison
  (`MessageDigest.isEqual` — timing-leak safe), 60s expiry leeway for
  restart skew, startup fails hard if the secret is < 32 chars.
- **Login = credential exchange**: `POST /api/auth/login` returns
  `{accessToken, tokenType, expiresIn, user}`. No sessions, no cookies,
  CSRF surface stays empty.
- **Why HS256, not RS256**: signer = verifier = this one service. A
  symmetric secret via `JWT_SECRET` is the simplest correct option.
  The moment a second service must verify tokens, switch to RS256
  (verifiers hold only the public key) — a drop-in change inside
  `JwtService`, callers untouched.

## Consequences

- (+) Stateless horizontally; instances need no shared session store.
- (+) Suspension semantics are immediate and simple — stronger than a
  revocation list for the "fired employee with a live token" case.
- (+) Zero new dependencies; whole mechanism ~150 auditable lines.
- (−) No refresh tokens or revocation table: a leaked token is valid
  until expiry (1h default). Acceptable at this scale; a revocation
  table is the documented next increment.
- (−) Symmetric secret shared by all verifiers — fine for one service,
  wrong for many (see RS256 above).

## Verification

- AuthHardeningIT: round-trip, garbage, wrong-secret, expired tokens;
  HMAC tamper matrix.
- ApiSecurityIT: tampered + expired tokens over real HTTP → 401;
  all previous 401/403/IDOR guarantees intact.
- tools/smoke.ps1 (live): login → Bearer → full flow; tampered token → 401.
