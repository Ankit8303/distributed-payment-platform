import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '15s', target: 20 },
    { duration: '30s', target: 50 },
    { duration: '15s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<150', 'p(99)<300'],
    http_req_failed: ['rate<0.02'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export default function () {
  const payload = JSON.stringify({
    username: 'perf_test_user@paymentledger.com',
    password: 'Password123!',
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
  };

  const res = http.post(`${BASE_URL}/api/v1/auth/login`, payload, params);

  check(res, {
    'login status is 200 or 401': (r) => r.status === 200 || r.status === 401,
  });

  if (res.status === 200) {
    const accessToken = res.json('accessToken');
    const refreshToken = res.json('refreshToken');

    check(res, {
      'has access token': () => accessToken !== undefined,
      'has refresh token': () => refreshToken !== undefined,
    });

    // Benchmark atomic token refresh
    if (refreshToken) {
      const refreshPayload = JSON.stringify({ refreshToken });
      const refreshRes = http.post(`${BASE_URL}/api/v1/auth/refresh`, refreshPayload, params);
      check(refreshRes, {
        'refresh status is 200': (r) => r.status === 200,
      });
    }
  }

  sleep(0.2);
}
