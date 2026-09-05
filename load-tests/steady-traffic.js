// Steady-traffic browse+reserve mix (moderate load profile for §47):
// 80% catalog/inventory reads, 20% reservations on a large section.
//
// Run: .\tools\k6.exe run -e EVENT_ID=<uuid> load-tests/steady-traffic.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE, USERS, authHeaders, ctr } from './helpers.js';

const EVENT_ID = __ENV.EVENT_ID;
const SECTION = __ENV.SECTION || 'BALCONY';

export const options = {
  scenarios: {
    steady: {
      executor: 'ramping-vus',
      startVUs: 2,
      stages: [
        { duration: '15s', target: 20 },
        { duration: '30s', target: 20 },
        { duration: '10s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
  },
};

export default function () {
  if (!EVENT_ID) return;
  const user = USERS[(__VU - 1) % USERS.length];
  const headers = authHeaders(user);

  const events = http.get(`${BASE}/api/events`, { headers });
  check(events, { 'events 200': (r) => r.status === 200 });

  const inv = http.get(`${BASE}/api/inventory?eventId=${EVENT_ID}`, { headers });
  check(inv, { 'inventory 200': (r) => r.status === 200 });

  if (__VU % 5 === 0) {
    // ~20% of VUs attempt a reservation on the big section.
    const res = http.post(
      `${BASE}/api/reservations`,
      JSON.stringify({ eventId: EVENT_ID, section: SECTION, quantity: 1 }),
      {
        headers: Object.assign(
          { 'Content-Type': 'application/json', 'Idempotency-Key': `steady-${user}-${__ITER}` },
          headers,
        ),
      },
    );
    if (res.status === 201) ctr.success.add(1);
    else if (res.status === 409) ctr.unavailable.add(1);
  }
  sleep(1);
}
