import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '15s', target: 50 },
    { duration: '30s', target: 150 },
    { duration: '15s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<80', 'p(99)<200'],
    http_req_failed: ['rate<0.01'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const AUTH_TOKEN = __ENV.AUTH_TOKEN || 'Bearer eyJhbGciOiJIUzI1NiJ9.perf-test-token';
const ACCOUNT_ID = __ENV.ACCOUNT_ID || '11111111-1111-1111-1111-111111111111';

export default function () {
  const params = {
    headers: {
      'Authorization': AUTH_TOKEN,
      'Content-Type': 'application/json',
    },
  };

  // 1. Account details lookup (exercises Redis read cache with PostgreSQL fallback)
  const resAccount = http.get(`${BASE_URL}/api/v1/accounts/${ACCOUNT_ID}`, params);
  check(resAccount, {
    'account status is 200 or 404': (r) => r.status === 200 || r.status === 404,
  });

  // 2. Account balance check
  const resBalance = http.get(`${BASE_URL}/api/v1/accounts/${ACCOUNT_ID}/balance`, params);
  check(resBalance, {
    'balance status is 200 or 404': (r) => r.status === 200 || r.status === 404,
  });

  sleep(0.1);
}
