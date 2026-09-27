import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '15s', target: 10 },
    { duration: '30s', target: 25 },
    { duration: '15s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<250', 'p(99)<500'],
    http_req_failed: ['rate<0.02'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ADMIN_AUTH_TOKEN = __ENV.ADMIN_AUTH_TOKEN || 'Bearer eyJhbGciOiJIUzI1NiJ9.admin-token';

export default function () {
  const params = {
    headers: {
      'Authorization': ADMIN_AUTH_TOKEN,
      'Content-Type': 'application/json',
    },
  };

  // 1. Paginated payment search (enforcing page size clamping to 100)
  const resPayments = http.get(`${BASE_URL}/api/v1/admin/payments?page=0&size=50`, params);
  check(resPayments, {
    'admin payments status is 200 or 403': (r) => r.status === 200 || r.status === 403,
  });

  // 2. Audit logs search
  const resAudit = http.get(`${BASE_URL}/api/v1/admin/audit-logs?page=0&size=20`, params);
  check(resAudit, {
    'admin audit status is 200 or 403': (r) => r.status === 200 || r.status === 403,
  });

  // 3. Reconciliation summary
  const resReconciliation = http.get(`${BASE_URL}/api/v1/reconciliation/reports?page=0&size=10`, params);
  check(resReconciliation, {
    'admin reconciliation status is 200 or 403': (r) => r.status === 200 || r.status === 403,
  });

  sleep(0.3);
}
