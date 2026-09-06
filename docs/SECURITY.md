# Security Model & Threat Assessment

## Authentication / authorization

- **Auth**: JWT bearer tokens (HS256, standard JWS compact serialization,
  JDK-only implementation — no external library). `POST /api/auth/login`
  validates credentials via the `AuthenticationManager` and returns
  `{accessToken, tokenType, expiresIn, user}`; every API call presents
  `Authorization: Bearer <token>`. Stateless:
  `SessionCreationPolicy.STATELESS`, no cookies. TLS remains a deployment
  concern (terminate at the LB/ingress).
  - **Why HS256**: single issuer = single verifier (this service), so a
    symmetric shared secret is the simplest correct option. Multi-service
    deployments switch to RS256 (verifiers hold the public key only) by
    replacing `JwtService` — no caller changes (see Known limitations).
  - **Token binding**: subject = email; custom claims carry the numeric DB
    user id + role, so ownership checks (userId scoping in services) are
    completely auth-mechanism-agnostic.
  - **Fresh-principal reload**: `JwtAuthFilter` re-loads the user from the
    DB on every authenticated request — suspension/role changes take
    effect immediately; a valid token alone can never vouch for standing.
  - **Verification discipline**: constant-time signature comparison
    (`MessageDigest.isEqual`), 60s expiry leeway for restart skew, startup
    fails hard if the secret is < 32 chars. Tampered, expired, and
    wrong-secret tokens all verify to null → 401 (AuthHardeningIT).
- **Passwords**: bcrypt (`BCryptPasswordEncoder`), cost 10. Seed hashes are
  of the literal demo password `password` and are documented as demo-only.
  Login never returns password material; tokens carry no secrets.
- **Account standing**: `SUSPENDED` accounts are rejected at
  `loadUserByUsername` (`DisabledException`) — and because the JWT filter
  re-loads the principal per request, suspension kills access on the very
  next call even with a live token.
- **Roles**: `USER`, `ADMIN` (DB CHECK-constrained). Enforced by
  `SecurityConfig`: `/api/admin/**` and `/actuator/**` (beyond health/info)
  require `ROLE_ADMIN`; everything under `/api/**` requires authentication;
  `/api/payment-webhooks` (HMAC below) and `/api/auth/**` are public by
  design.

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
information. The inventory lookup-by-id endpoint (which originally took a
sequential DB id) was replaced with UUID lookup during the audit.

## Injection safety

- All SQL is JPQL with bound parameters or Spring Data derived queries.
- The one native statement (idempotency claim) is fully parameterized.
- ApiSecurityIT fires a classic `' OR '1'='1` section injection: the
  parameterized lookup simply fails to find the section (404), no query
  is altered.

## Webhook hardening (`/api/payment-webhooks`)

Public endpoint by design (PSP callbacks cannot authenticate as users);
hardened by:

- **HMAC-SHA256 signature enforcement**: every delivery MUST carry
  `X-Signature` — base64(HMAC-SHA256(raw payload bytes, shared secret)).
  Verification happens over the **exact raw bytes** (a byte-preserving
  capture filter wraps the request before JSON parsing, so re-serialization
  can never break a signature) using a constant-time comparison. Unsigned
  or tampered deliveries → 401 `WEBHOOK_SIGNATURE_INVALID` **before any
  parsing or state change**. The secret (`PAYMENT_WEBHOOK_SECRET`) is
  env-injected, must be ≥ 32 chars (startup fails otherwise), and is never
  logged.
  - The mock gateway's relay signs every simulated delivery with the same
    secret, so the verification path runs on every payment in the system,
    and a misconfigured secret fails fast in tests, not in production.
- Strict validation before any state change: non-null UUID orderId,
  providerRef 1–128 chars, outcome ∈ {SUCCESS, FAILURE, TIMEOUT} —
  violations are 400 with no side effects.
- **Idempotent by construction**: duplicates produce only
  DUPLICATE_CALLBACK/LATE_CALLBACK attempt rows (audit-visible), never a
  second business effect.
- Late/garbage callbacks cannot corrupt state: terminal orders absorb
  them; unknown orders 404. A correctly signed callback for an unknown
  order passes the HMAC gate and then 404s — signature proves origin, not
  authorization (AuthHardeningIT pins this exact distinction).

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
| Forged / tampered JWT | HS256 constant-time verify, tamper+expiry+wrong-secret rejected | AuthHardeningIT, ApiSecurityIT |
| Stolen-token use after account suspension | fresh principal reload per request | (unit-level, loadUserByUsername) |
| Unsigned / tampered payment callback | HMAC-SHA256 over raw bytes, constant-time compare, 401 pre-parse | AuthHardeningIT (over real HTTP) |
| Malicious payment callback (forged/refunded) | strict validation + idempotent application + HMAC | PaymentFlowIT |
| Duplicate payment callbacks (double-confirm) | order lock + payment state filter | PaymentFlowIT (8-way concurrent) |
| Duplicate / replayed reservation requests | idempotency keys + fingerprints | ApiIdempotencyIT, ConcurrentIdempotencyClaimIT |
| Event spoofing / duplicate delivery | processed_event dedup marker | EventHandlersTest |
| SQL injection | parameterized queries only | ApiSecurityIT |
| Secret leakage | .env gitignored, structured errors, no traces in responses | review |
| Rate-limit bypass attempt flood | Redis token bucket per user | flash-sale run (429s observed) |
| Malformed event payload | immediate dead_letter quarantine | DeadLetterServiceTest |
| Malformed request that trips the strict HTTP firewall | RequestRejectedException → structured 400 | (verified live, smoke) |
| Privilege escalation | role stored in DB with CHECK, set only at registration (USER) | ApiSecurityIT |
| Unauthorized admin actions | `/api/admin/**` ROLE_ADMIN | ApiSecurityIT |
| Enumeration of ids | public UUID identifiers | (design) |
| Suspended account usage | DisabledException at auth + per-request reload | (unit-level, loadUserByUsername) |

## Known limitations (documented, deliberate)

- JWT uses HS256 (symmetric): correct for a single-service system; a
  multi-service deployment would switch to RS256 so verifiers never hold
  the signing key (drop-in change inside `JwtService`).
- No token revocation list / refresh-token rotation: tokens are short-lived
  (1h default) and suspension is enforced per-request via the fresh
  principal reload — a revocation table would be the next addition.
- Webhook HMAC assumes a single PSP relationship (one shared secret);
  multi-PSP setups would key the secret per provider.
- No account lockout after repeated bad logins (rate limiting is on the
  reservation path; an auth-attempt limiter would be the next addition).
- TLS is external to the app (terminate at LB/ingress) — tokens must not
  cross untrusted hops in clear.
