// Shared helpers for Synchros load tests.
import http from 'k6/http';
import { Counter } from 'k6/metrics';

export const BASE = __ENV.BASE_URL || 'http://localhost:8081';

export const USERS = [
  'alice@example.com',
  'bob@example.com',
  'admin@synchros.dev',
];

export const PASSWORD = __ENV.USER_PASSWORD || 'password';

/**
 * Logs in via /api/auth/login and returns { Authorization: 'Bearer ...' }.
 * Tokens are cached per VU (k6 JS state is VU-isolated) with the expiry
 * the server reports — a cached token is re-logged-in once it is within
 * 60s of expiring, so long benchmarks (JWT TTL default 1h) never fire on
 * a stale token mid-run.
 */
const tokenCache = {};

export function loginHeaders(email) {
  const cached = tokenCache[email];
  if (cached && Date.now() < cached.expiresAt) return cached.headers;

  const res = http.post(
    `${BASE}/api/auth/login`,
    JSON.stringify({ email, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  if (res.status !== 200) {
    throw new Error(`login failed for ${email}: HTTP ${res.status}`);
  }
  const body = res.json();
  const headers = { Authorization: `Bearer ${body.accessToken}` };
  tokenCache[email] = {
    headers,
    // expiresIn is seconds; refresh 60s early so no in-flight call
    // crosses the expiry boundary.
    expiresAt: Date.now() + (body.expiresIn - 60) * 1000,
  };
  return headers;
}

// Kept for backward compatibility with scripts that pre-provision users
// (each fresh user logs in exactly once before the contention phase).
export function authHeaders(user) {
  return loginHeaders(user);
}

// Business outcome counters (distinct from k6's protocol-level metrics).
export const ctr = {
  success: new Counter('synchros_reservation_success'),
  unavailable: new Counter('synchros_reservation_unavailable'),
  rateLimited: new Counter('synchros_rate_limited'),
  idempotentReplay: new Counter('synchros_idempotent_replay'),
};
