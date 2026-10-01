import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { BASE_URL, generateNotificationPayload } from '../config.js';

export const acceptedCounter = new Counter('notifications_accepted_total');
export const failedRate = new Rate('notifications_failed_rate');
export const ingestionLatency = new Trend('notification_ingestion_latency_ms');

export const options = {
  scenarios: {
    burst_10k: {
      executor: 'shared-iterations',
      vus: 50,
      iterations: 10000,
      maxDuration: '2m',
    },
  },
  thresholds: {
    'http_req_failed': ['rate<0.01'],               // Lỗi HTTP < 1%
    'http_req_duration': ['p(95)<1000', 'p(99)<2000'], // 95% request ghi Outbox < 1000ms
    'notifications_failed_rate': ['rate<0.01'],
  },
};

export default function () {
  const payload = JSON.stringify(generateNotificationPayload());

  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
  };

  const start = Date.now();
  const res = http.post(`${BASE_URL}/api/v1/notifications`, payload, params);
  const duration = Date.now() - start;

  ingestionLatency.add(duration);

  const isSuccess = check(res, {
    'status is 202 ACCEPTED': (r) => r.status === 202,
    'has notification id in body': (r) => {
      try {
        const body = JSON.parse(r.body);
        return (body.id || body.notificationId) !== undefined;
      } catch (e) {
        return false;
      }
    },
  });

  if (isSuccess) {
    acceptedCounter.add(1);
    failedRate.add(0);
  } else {
    failedRate.add(1);
    console.error(`Request failed with status ${res.status}: ${res.body}`);
  }
}
