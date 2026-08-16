/**
 * k6 load test: the double-booking proof.
 *
 *   docker run --rm -i --network host grafana/k6 run - < loadtest/booking-race.js
 *   (or, with k6 installed locally)  k6 run loadtest/booking-race.js
 *
 * 500 virtual users all try to book the SAME slot at the SAME moment.
 *
 * Pass condition: exactly one HTTP 201, every other response a clean
 * 409 SLOT_TAKEN, and zero 5xx. If the exclusion constraint were missing this
 * would produce dozens of overlapping bookings; if we had used a naive
 * "check then insert" it would produce a handful. The point of the test is
 * that the number is 1, every single run.
 */
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const TENANT = __ENV.TENANT || 'demo-clinic';

const booked = new Counter('slot_won');
const conflicted = new Counter('slot_conflict');
const serverErrors = new Counter('server_errors');

export const options = {
  scenarios: {
    stampede: {
      executor: 'per-vu-iterations',
      vus: 500,
      iterations: 1,
      maxDuration: '60s',
      // Everyone starts together - a ramp-up would spread the requests out and
      // there would be no race left to test.
      gracefulStop: '10s',
    },
  },
  thresholds: {
    slot_won: ['count==1'],
    server_errors: ['count==0'],
    http_req_failed: ['rate<1'],
  },
};

/**
 * Runs once before the VUs start: pick a resource and a free slot, and grab
 * enough customers that every VU can book as a different person.
 */
export function setup() {
  const headers = { 'X-Tenant-Slug': TENANT, 'Content-Type': 'application/json' };

  const resources = http.get(`${BASE}/api/v1/resources`, { headers }).json();
  const resourceId = resources[0].id;

  const customers = http.get(`${BASE}/api/v1/customers`, { headers }).json();

  // A slot far enough in the future that repeated runs do not collide with the
  // seeded demo bookings.
  const day = new Date();
  day.setDate(day.getDate() + 7);
  const date = day.toISOString().slice(0, 10);

  const slots = http
    .get(`${BASE}/api/v1/resources/${resourceId}/availability?date=${date}`, { headers })
    .json();

  const open = slots.find((s) => s.status === 'OPEN');
  if (!open) {
    throw new Error(`No open slot on ${date} - pick another day`);
  }

  return {
    resourceId,
    startsAt: open.startsAt,
    customerIds: customers.map((c) => c.id),
  };
}

export default function (data) {
  const headers = {
    'X-Tenant-Slug': TENANT,
    'Content-Type': 'application/json',
    // A distinct key per virtual user: we want 500 genuinely different
    // requests racing, not one request being de-duplicated 500 times.
    'Idempotency-Key': `k6-${exec.scenario.iterationInTest}-${exec.vu.idInTest}`,
  };

  const customerId = data.customerIds[exec.vu.idInTest % data.customerIds.length];

  const response = http.post(
    `${BASE}/api/v1/bookings`,
    JSON.stringify({
      resourceId: data.resourceId,
      customerId,
      startsAt: data.startsAt,
    }),
    { headers },
  );

  if (response.status === 201) {
    booked.add(1);
  } else if (response.status === 409) {
    conflicted.add(1);
  } else if (response.status >= 500) {
    serverErrors.add(1);
  }

  check(response, {
    'no server error': (r) => r.status < 500,
    'created or conflicted': (r) => r.status === 201 || r.status === 409,
  });
}

export function teardown(data) {
  console.log(`Raced for slot ${data.startsAt} on resource ${data.resourceId}`);
}
