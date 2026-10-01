import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import { BASE_URL, generateNotificationPayload } from '../config.js';

export const acceptedCounter = new Counter('rate_limit_accepted_total');
export const failedRate = new Rate('rate_limit_failed_rate');

export const options = {
  scenarios: {
    constant_rate_flood: {
      executor: 'constant-arrival-rate',
      rate: 150,           // 150 requests / giây (vượt qua giới hạn 100 req/s của provider)
      timeUnit: '1s',
      duration: '45s',
      preAllocatedVUs: 50,
      maxVUs: 150,
    },
  },
  thresholds: {
    'http_req_failed': ['rate<0.01'],
    'http_req_duration': ['p(95)<1000'],
  },
};

export default function () {
  // Gửi dồn dập vào kênh PUSH và EMAIL
  const payload = JSON.stringify(generateNotificationPayload({
    channel: Math.random() > 0.5 ? 'PUSH' : 'EMAIL',
    priority: 'HIGH',
    subject: `[RateLimit Test] Batch payload #${Date.now()}`
  }));

  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
  };

  const res = http.post(`${BASE_URL}/api/v1/notifications`, payload, params);

  const isSuccess = check(res, {
    'status is 202 ACCEPTED': (r) => r.status === 202,
  });

  if (isSuccess) {
    acceptedCounter.add(1);
    failedRate.add(0);
  } else {
    failedRate.add(1);
  }
}
