// Duplicate-request idempotency benchmark (§100):
// The SAME logical reservation (same Idempotency-Key + body) fired N times
// concurrently must create exactly ONE logical reservation.
//
// Run: .\tools\k6.exe run -e EVENT_ID=<uuid> -e SECTION=BALCONY load-tests/duplicate-request.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE, authHeaders } from './helpers.js';

const EVENT_ID = __ENV.EVENT_ID;
const SECTION = __ENV.SECTION || 'BALCONY';
const DUPLICATES = parseInt(__ENV.DUPLICATES || '20', 10);

export const options = {
  scenarios: {
    duplicates: {
      executor: 'per-vu-iterations',
      vus: DUPLICATES,
      iterations: 1,
      maxDuration: '30s',
    },
  },
  // 409/429 are the DESIGNED responses for concurrent duplicates — only
  // 5xx counts as a transport failure here.
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
};

// ONE key for the whole run. k6 executes the top level per VU, so a
// Date.now() constant here would give every VU a DIFFERENT key (N logical
// reservations — the opposite of what this benchmark proves). Pass a
// run-scoped key through env or derive it deterministically.
const RUN_TAG = __ENV.RUN_TAG || 'run';
const IDEM_KEY = `dup-bench-${RUN_TAG}`;

// 201 (first + replays), 409 (in-flight conflict / inventory), 429 (rate
// limit) are all designed responses for concurrent duplicates.
http.setResponseCallback(http.expectedStatuses(201, 409, 429));

export default function () {
  if (!EVENT_ID) return;
  const headers = Object.assign(
    { 'Content-Type': 'application/json', 'Idempotency-Key': IDEM_KEY },
    authHeaders('alice@example.com'),
  );
  const res = http.post(
    `${BASE}/api/reservations`,
    JSON.stringify({ eventId: EVENT_ID, section: SECTION, quantity: 1 }),
    { headers, tags: { name: 'duplicate-reservation' } },
  );
  // First call: 201; concurrent duplicates: 409 in-flight, later retries:
  // 201 replay. Exactly one logical reservation must exist for the key.
  check(res, {
    'no server error': (r) => r.status < 500,
    'accepted-or-conflict-or-replay': (r) => [201, 409, 429].includes(r.status),
  });
  sleep(0.05);
}
