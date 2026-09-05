// Shared helpers for FlashReserve load tests.
import { Counter } from 'k6/metrics';

export const BASE = __ENV.BASE_URL || 'http://localhost:8081';

export const USERS = [
  'alice@example.com',
  'bob@example.com',
  'admin@flashreserve.dev',
];

// Minimal base64 (k6 has no btoa).
const CHARS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
export function b64(s) {
  let out = '';
  for (let i = 0; i < s.length; i += 3) {
    const b1 = s.charCodeAt(i);
    const b2 = s.charCodeAt(i + 1);
    const b3 = s.charCodeAt(i + 2);
    const has2 = i + 1 < s.length;
    const has3 = i + 2 < s.length;
    out += CHARS[b1 >> 2];
    out += CHARS[((b1 & 3) << 4) | (has2 ? b2 >> 4 : 0)];
    out += !has2 ? '=' : CHARS[((b2 & 15) << 2) | (has3 ? b3 >> 6 : 0)];
    out += !has3 ? '=' : CHARS[b3 & 63];
  }
  return out;
}

export function authHeaders(user) {
  return { Authorization: `Basic ${b64(user + ':password')}` };
}

// Business outcome counters (distinct from k6's protocol-level metrics).
export const ctr = {
  success: new Counter('flashreserve_reservation_success'),
  unavailable: new Counter('flashreserve_reservation_unavailable'),
  rateLimited: new Counter('flashreserve_rate_limited'),
  idempotentReplay: new Counter('flashreserve_idempotent_replay'),
};
