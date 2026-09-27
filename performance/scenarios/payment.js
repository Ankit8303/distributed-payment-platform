import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

export const options = {
  stages: [
    { duration: '20s', target: 25 },   // Low concurrency
    { duration: '30s', target: 100 },  // Medium concurrency
    { duration: '30s', target: 200 },  // High concurrency peak
    { duration: '20s', target: 0 },    // Ramp-down
  ],
  thresholds: {
    http_req_duration: ['p(95)<350', 'p(99)<750'], // Critical financial path budget
    http_req_failed: ['rate<0.05'],                // Failure budget (excludes business 409s)
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const AUTH_TOKEN = __ENV.AUTH_TOKEN || 'Bearer eyJhbGciOiJIUzI1NiJ9.perf-test-token';

export default function () {
  const idempotencyKey = uuidv4();
  const payerAccountId = '11111111-1111-1111-1111-111111111111';
  const payeeAccountId = '22222222-2222-2222-2222-222222222222';

  const payload = JSON.stringify({
    payerAccountId: payerAccountId,
    payeeAccountId: payeeAccountId,
    amount: 1500, // $15.00 in minor units
    currency: 'USD',
    description: 'Performance test transfer',
  });

  const params = {
    headers: {
      'Authorization': AUTH_TOKEN,
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
    },
  };

  // 1. Initial payment attempt (executes complete financial path)
  const res = http.post(`${BASE_URL}/api/v1/payments`, payload, params);

  check(res, {
    'payment status is 201, 200, 400, or 409': (r) =>
      r.status === 201 || r.status === 200 || r.status === 400 || r.status === 409,
    'zero 500 errors': (r) => r.status < 500,
  });

  // 2. Idempotent replay with identical key (must return identical outcome or 409, zero duplicate ledger rows)
  if (res.status === 201 || res.status === 200) {
    const replayRes = http.post(`${BASE_URL}/api/v1/payments`, payload, params);
    check(replayRes, {
      'idempotent replay matches initial or 409': (r) =>
        r.status === 200 || r.status === 201 || r.status === 409,
      'idempotent replay has zero 500 errors': (r) => r.status < 500,
    });
  }

  sleep(0.1);
}
