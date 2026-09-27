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
const MERCHANT_ACCOUNT_ID = __ENV.ACCOUNT_ID || '44444444-4444-4444-4444-444444444444';

export default function () {
  const idempotencyKey = uuidv4();

  const payload = JSON.stringify({
    accountId: MERCHANT_ACCOUNT_ID,
    amount: 10000, // $100.00 in minor units
    currency: 'USD',
    destinationAccount: 'bank_account_ending_9999',
  });

  const params = {
    headers: {
      'Authorization': AUTH_TOKEN,
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
    },
  };

  const res = http.post(`${BASE_URL}/api/v1/payouts`, payload, params);

  check(res, {
    'payout status is 201, 200, 400, or 409': (r) =>
      r.status === 201 || r.status === 200 || r.status === 400 || r.status === 409,
    'payout zero 500 errors': (r) => r.status < 500,
  });

  sleep(0.2);
}
