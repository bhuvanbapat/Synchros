// Shared helpers for FlashReserve load tests.
import http from 'k6/http';
import { Counter } from 'k6/metrics';

export const BASE = __ENV.BASE_URL || 'http://localhost:8081';

export const USERS = [
  'alice@example.com',
  'bob@example.com',
  'admin@flashreserve.dev',
];

export const PASSWORD = __ENV.USER_PASSWORD || 'password';

/**
 * Logs in via /api/auth/login and returns { Authorization: 'Bearer ...' }.
 * Tokens are cached per VU (k6 JS state is VU-isolated): one login, many
 * authenticated calls — exactly like the browser client.
 */
const tokenCache = {};

export function loginHeaders(email) {
  if (tokenCache[email]) return tokenCache[email];
  const res = http.post(
    `${BASE}/api/auth/login`,
    JSON.stringify({ email, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  if (res.status !== 200) {
    throw new Error(`login failed for ${email}: HTTP ${res.status}`);
  }
  const body = res.json();
  tokenCache[email] = { Authorization: `Bearer ${body.accessToken}` };
  return tokenCache[email];
}

// Kept for backward compatibility with scripts that pre-provision users
// (each fresh user logs in exactly once before the contention phase).
export function authHeaders(user) {
  return loginHeaders(user);
}

// Business outcome counters (distinct from k6's protocol-level metrics).
export const ctr = {
  success: new Counter('flashreserve_reservation_success'),
  unavailable: new Counter('flashreserve_reservation_unavailable'),
  rateLimited: new Counter('flashreserve_rate_limited'),
  idempotentReplay: new Counter('flashreserve_idempotent_replay'),
};
