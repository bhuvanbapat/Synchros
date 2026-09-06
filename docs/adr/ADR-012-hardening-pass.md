# ADR-012: Webhook HMAC, opt-in tracing, verified Kafka HA

**Status:** Accepted

## Context

Three gaps were documented as deliberate limitations and are now
closed: (1) the payment webhook was a public unauthenticated
state-mutating endpoint, (2) OpenTelemetry was deferred in favor of
correlation IDs, (3) the Kafka HA topology existed only as prose.

## Decision

**Webhook HMAC-SHA256 (mandatory).** Every delivery to
`/api/payment-webhooks` must carry `X-Signature`:
base64(HMAC-SHA256(raw request bytes, `PAYMENT_WEBHOOK_SECRET`)).

- Verification runs on the **exact raw bytes** — `RawBodyCaptureFilter`
  wraps the request (scoped to the webhook path only) before Jackson
  parsing, because JSON round-trips are not byte-stable and would break
  signatures.
- Constant-time compare; startup fails if the secret is < 32 chars.
- Unsigned/tampered → 401 `WEBHOOK_SIGNATURE_INVALID` **before any
  parsing or state change** — the reject path touches zero business
  logic.
- The mock relay signs every simulated PSP delivery with the same
  secret, so the verification path executes on every payment in the
  system — a misconfigured secret fails in tests, not production.
- Stripe-style shared-secret model: right for one PSP relationship;
  multi-PSP deployments key secrets per provider and add a replay
  window (timestamp header).

**OpenTelemetry (opt-in).** micrometer-tracing bridge + OTLP exporter
behind `OTEL_TRACING_ENABLED` + `OTEL_EXPORTER_OTLP_ENDPOINT`. Disabled
by default: no exporter configured, zero overhead, correlation IDs
remain the always-on baseline. The HA compose ships an OTel Collector
profile (`--profile tracing`) with a console exporter to swap for
Jaeger/Tempo. Opt-in keeps local dev and CI hermetic while making
"point at a real collector" a config change, not a code change.

**Kafka HA topology (verified, not just documented).**
`docker-compose.ha.yml`: 3-broker KRaft cluster (combined broker+
controller per node), RF=3, min.insync.replicas=2. The verification was
a live drill: produce 10 messages → kill the events-topic leader →
leadership failed over, ISR shrank 3→2, all 10 messages remained
consumable, the killed broker rejoined cleanly on restart. The
single-broker compose stays the dev default (footprint); the HA file is
the production-shape reference.

## Consequences

- (+) The webhook's trust model now matches real PSP integrations;
  forging requires the secret, not knowledge of the endpoint.
- (+) Tracing is available without imposing infra on every environment.
- (+) The HA claim is measurement, not prose.
- (−) HMAC binds origin, not authorization: a correctly signed forged
  SUCCESS still applies — per-PSP secrets + payload validation remain
  the defense in depth (idempotency and state machines absorb the rest).
- (−) No replay window in the signature scheme — idempotency neutralizes
  the state effect of a raw replay, but the attempt row is still written.
- (−) Tracing default-off means nobody looks at spans until someone
  turns it on; correlation IDs keep the debug story intact meanwhile.

## Verification

- AuthHardeningIT: HMAC round-trip/tamper/garbage; unsigned + tampered
  webhooks 401 over real HTTP; signed-but-unknown-order passes HMAC
  then 404s (origin ≠ authorization).
- Live chaos drill (docker-compose.ha.yml): leader killed, zero loss.
- tools/smoke.ps1: full flow green with JWT + HMAC in the path.
