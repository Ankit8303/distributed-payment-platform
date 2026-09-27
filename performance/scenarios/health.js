import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '10s', target: 50 },  // Ramp up to 50 VUs
    { duration: '20s', target: 100 }, // Sustained load
    { duration: '10s', target: 0 },   // Ramp down
  ],
  thresholds: {
    http_req_duration: ['p(95)<50', 'p(99)<100'], // 95% under 50ms, 99% under 100ms
    http_req_failed: ['rate<0.01'],              // Error rate under 1%
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export default function () {
  // Test Actuator Health
  const resLiveness = http.get(`${BASE_URL}/actuator/health/liveness`);
  check(resLiveness, {
    'liveness status is 200': (r) => r.status === 200,
    'liveness returns UP': (r) => r.json('status') === 'UP',
  });

  const resReadiness = http.get(`${BASE_URL}/actuator/health/readiness`);
  check(resReadiness, {
    'readiness status is 200': (r) => r.status === 200,
    'readiness returns UP': (r) => r.json('status') === 'UP',
  });

  sleep(0.1);
}
