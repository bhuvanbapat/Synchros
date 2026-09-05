# Security Model & Threat Assessment

## Authentication / authorization

- **Auth**: HTTP Basic over TLS in a real deployment (portfolio choice —
  swap for JWT/OAuth2 without touching ownership logic, which lives in
  services). Stateless: `SessionCreationPolicy.STATELESS`, no cookies.
- **Passwords**: bcrypt (`BCryptPasswordEncoder`), cost 10. Seed hashes are
  of the literal demo password `password` and are documented as demo-only.
- **Account standing**: `SUSPENDED` accounts are rejected at
  `loadUserByUsername` (`DisabledException`) — auth checks standing, not
  just credentials.
- **Roles**: `USER`, `ADMIN` (DB CHECK-constrained). Enforced by
  `SecurityConfig`: `/api/admin/**` and `/actuator/**` (beyond health/info)
  require `ROLE_ADMIN`; everything under `/api/**` requires authentication;
  `/api/payment-webhooks` and `/api/auth/**` are public by design.

## Ownership (anti-IDOR)

Every user-scoped read/mutation resolves the row **with the caller's userId
in the query**:

- `ReservationService.getByPublicIdForUser` / `lockByPublicIdOwned`
- `OrderService.getByPublicIdForUser`
- `lockByPublicIdAndOwner` (order creation) — ownership folded into the
  locking query, so a foreign reservation can never be locked or acted on

Changing an ID in a URL yields 403 `FORBIDDEN`, not the other user's data
(tested: ApiSecurityIT — bob cannot read alice's reservation).

Public UUIDs everywhere (never sequential DB ids) — enumeration leaks no
information. `inventory_item`'s raw-id endpoint was replaced with UUID
lookup during the audit.

## Injection safety

- All SQL is JPQL with bound parameters or Spring Data derived queries.
- The one native statement (idempotency claim) is fully parameterized.
- ApiSecurityIT fires a classic `' OR '1'='1` section injection: the
  parameterized lookup simply fails to find the section (404), no query
  is altered.

## Webhook hardening (`/api/payment-webhooks`)

Public endpoint by design (PSP callbacks cannot authenticate as users);
hardened by:

- Strict validation before any state change: non-null UUID orderId,
  providerRef 1–128 chars, outcome ∈ {SUCCESS, FAILURE, TIMEOUT} —
  violations are 400 with no side effects.
- **Idempotent by construction**: duplicates produce only
  DUPLICATE_CALLBACK/LATE_CALLBACK attempt rows (audit-visible), never a
  second business effect.
- Late/garbage callbacks cannot corrupt state: terminal orders absorb
  them; unknown orders 404.
- Documented production step (deliberately not simulated here): verify an
  HMAC-SHA256 signature header against the PSP secret, constant-time
  compare, per-provider replay window. The controller is the single point
  where that check slots in.

## Input validation

Bean Validation on every DTO: `@Email @NotBlank`, password size bounds,
`@Min(1) @Max(10)` quantity, `@NotNull` UUIDs. Malformed JSON → 400 with
structured error. All error responses share the stable model
`{code, message, requestId, timestamp}` — no stack traces, no internal
details leak (GlobalExceptionHandler).

## CSRF / CORS / sessions

- CSRF disabled: no cookies, pure header-token API (documented rationale).
- CORS: explicit origin **allow-list** (dev frontend origin, configurable
  via `FRONTEND_ORIGIN`), fixed method/header lists, no wildcard —
  wildcard would be fine for anonymous APIs but not with Basic credentials.
- No session fixation surface: no sessions at all.

## Abuse controls

- Redis token-bucket rate limit on `POST /api/reservations` (per user,
  atomic Lua): default 30/min, burst 10 → 429 `RATE_LIMIT_EXCEEDED`.
- Fails **open** on Redis absence: a cache/coordination layer must not be
  a single point of failure for the API; correctness is not at stake
  (oversell prevention is in Postgres).

## Secrets

- No credentials in the repo. `.env.example` documents local defaults;
  `.env` is gitignored; compose passwords are local-dev-only and labeled.
- Seed passwords are documented demo values.
- Logs never print password material, tokens, or full payment details;
  request IDs and IDs only.

## Threat → control matrix

| Threat | Control | Test |
|---|---|---|
| IDOR (user A reads B's reservation/order) | owner-scoped queries + locks | ApiSecurityIT |
| Unauthorized admin access | role check in filter chain | ApiSecurityIT |
| Duplicate / replayed reservation requests | idempotency keys + fingerprints | ApiIdempotencyIT, ConcurrentIdempotencyClaimIT |
| Malicious payment callback (forged/refunded) | strict validation + idempotent application + (prod: HMAC) | PaymentFlowIT |
| Duplicate payment callbacks (double-confirm) | order lock + payment state filter | PaymentFlowIT (8-way concurrent) |
| Event spoofing / duplicate delivery | processed_event dedup marker | EventHandlersTest |
| SQL injection | parameterized queries only | ApiSecurityIT |
| Secret leakage | .env gitignored, structured errors, no traces in responses | review |
| Rate-limit bypass attempt flood | Redis token bucket per user | flash-sale run (429s observed) |
| Malformed event payload | immediate dead_letter quarantine | DeadLetterServiceTest |
| Privilege escalation | role stored in DB with CHECK, set only at registration (USER) | ApiSecurityIT |
| Unauthorized admin actions | `/api/admin/**` ROLE_ADMIN | ApiSecurityIT |
| Enumeration of ids | public UUID identifiers | (design) |
| Suspended account usage | DisabledException at auth | (unit-level, loadUserByUsername) |

## Known limitations (documented, deliberate)

- HTTP Basic without TLS is demo-only; production requires TLS + JWT/OIDC.
- Webhook signature verification is documented but not simulated (there is
  no real PSP to sign).
- No account lockout after repeated bad logins (rate limiting is on the
  reservation path; an auth-attempt limiter would be the next addition).
