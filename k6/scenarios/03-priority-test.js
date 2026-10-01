import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { BASE_URL, generateNotificationPayload } from '../config.js';

export const lowPriorityCounter = new Counter('low_priority_ingested_total');
export const criticalPriorityCounter = new Counter('critical_priority_ingested_total');

export const options = {
  scenarios: {
    // Kịch bản 1: Bơm 3.000 tin LOW priority để lấp đầy queue
    flood_low_priority: {
      executor: 'shared-iterations',
      vus: 30,
      iterations: 3000,
      maxDuration: '1m',
      exec: 'sendLowPriority',
    },
    // Kịch bản 2: Bắt đầu sau 5 giây, bơm 300 tin CRITICAL priority
    inject_critical_priority: {
      executor: 'shared-iterations',
      vus: 10,
      iterations: 300,
      startTime: '5s',
      maxDuration: '1m',
      exec: 'sendCriticalPriority',
    },
  },
  thresholds: {
    'http_req_failed': ['rate<0.01'],
    'http_req_duration': ['p(95)<1000'],
  },
};

const params = {
  headers: {
    'Content-Type': 'application/json',
  },
};

export function sendLowPriority() {
  const payload = JSON.stringify(generateNotificationPayload({
    priority: 'LOW',
    channel: 'EMAIL',
    subject: `[LOW Priority] Background summary report #${Date.now()}`
  }));

  const res = http.post(`${BASE_URL}/api/v1/notifications`, payload, params);
  const ok = check(res, { 'LOW priority 202 Accepted': (r) => r.status === 202 });
  if (ok) lowPriorityCounter.add(1);
}

export function sendCriticalPriority() {
  const payload = JSON.stringify(generateNotificationPayload({
    priority: 'CRITICAL',
    channel: 'PUSH',
    subject: `[CRITICAL OTP] Urgent Verification Code #${Date.now()}`
  }));

  const res = http.post(`${BASE_URL}/api/v1/notifications`, payload, params);
  const ok = check(res, { 'CRITICAL priority 202 Accepted': (r) => r.status === 202 });
  if (ok) criticalPriorityCounter.add(1);
}
