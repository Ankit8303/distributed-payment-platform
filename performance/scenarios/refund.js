import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

export const options = {
  stages: [
    { duration: '15s', target: 20 },
    { duration: '30s', target: 50 },
    { duration: '15s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<300', 'p(99)<600'],
    http_req_failed: ['rate<0.05'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const AUTH_TOKEN = __ENV.AUTH_TOKEN || 'Bearer eyJhbGciOiJIUzI1NiJ9.perf-test-token';
const PAYMENT_ID = __ENV.PAYMENT_ID || '33333333-3333-3333-3333-333333333333';

export default function () {
  const idempotencyKey = uuidv4();

  const payload = JSON.stringify({
    paymentId: PAYMENT_ID,
    amount: 500, // $5.00 partial refund
    reason: 'Performance test refund',
  });

  const params = {
    headers: {
      'Authorization': AUTH_TOKEN,
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
    },
  };

  const res = http.post(`${BASE_URL}/api/v1/refunds`, payload, params);

  check(res, {
    'refund status is 201, 200, 400, or 409': (r) =>
      r.status === 201 || r.status === 200 || r.status === 400 || r.status === 409,
    'refund zero 500 errors': (r) => r.status < 500,
  });

  sleep(0.2);
}
