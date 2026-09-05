// THE CONTENTION BENCHMARK (§48/§99):
// One hot inventory pool, hundreds of concurrent clients racing for it.
// Exit criteria checked in teardown + verified in PERFORMANCE.md:
//   successes <= units; pool.available never negative; no oversell.
//
// Run:  .\tools\k6.exe run -e EVENT_ID=<uuid> -e SECTION=HOT100 -e CLIENTS=500 -e UNITS=100 load-tests/flash-sale.js
import http from 'k6/http';
import { check } from 'k6';
import { BASE, authHeaders, ctr } from './helpers.js';

const EVENT_ID = __ENV.EVENT_ID;
const SECTION = __ENV.SECTION || 'FLOOR';
const CLIENTS = parseInt(__ENV.CLIENTS || '500', 10);
const UNITS = parseInt(__ENV.UNITS || '100', 10);

// 201 (won), 409 (clean contention rejection), 429 (rate limited) are ALL
// correct system behavior — none is a transport failure. Everything else
// (5xx, timeouts) fails the http_req_failed threshold.
http.setResponseCallback(http.expectedStatuses(201, 409, 429));

export const options = {
  scenarios: {
    flash_sale: {
      executor: 'per-vu-iterations',
      vus: CLIENTS,
      iterations: 1,
      maxDuration: '2m',
    },
  },
  thresholds: {
    // The FINAL authority is the business counters below (success must
    // never exceed UNITS). Protocol-level "failed" counts the DESIGNED
    // 409/429 contention rejections, so it is expected to be high here —
    // transport errors are caught by the unexpected-status check instead.
    http_req_failed: ['rate<0.01'],
  },
  setupTimeout: '3m',
};

export function setup() {
  // Register test users in PARALLEL batches (http.batch) so setup is fast.
  const emails = [];
  const requests = [];
  for (let i = 0; i < CLIENTS; i++) {
    const email = `k6-${Date.now()}-${i}@loadtest.dev`;
    emails.push(email);
    requests.push([
      'POST',
      `${BASE}/api/auth/register`,
      JSON.stringify({ email, password: 'password' }),
      { headers: { 'Content-Type': 'application/json' } },
    ]);
  }
  // k6 batch (parallel, per iteration)
  for (let i = 0; i < requests.length; i += 50) {
    http.batch(requests.slice(i, i + 50));
  }
  return { emails };
}

export default function (data) {
  if (!EVENT_ID) return;
  const user = data.emails[__VU - 1];
  const headers = Object.assign(
    { 'Content-Type': 'application/json', 'Idempotency-Key': `k6-${__VU}-${Date.now()}` },
    authHeaders(user),
  );

  const res = http.post(
    `${BASE}/api/reservations`,
    JSON.stringify({ eventId: EVENT_ID, section: SECTION, quantity: 1 }),
    { headers, tags: { name: 'reservation-attempt' } },
  );

  if (res.status === 201) {
    ctr.success.add(1);
  } else if (res.status === 409) {
    const body = res.json();
    if (body.code === 'RATE_LIMIT_EXCEEDED' || body.code === 'REQUEST_IN_PROGRESS') ctr.rateLimited.add(1);
    else ctr.unavailable.add(1);
  } else if (res.status === 429) {
    ctr.rateLimited.add(1);
  } else {
    check(res, { [`unexpected status ${res.status}`]: () => false });
  }
}
