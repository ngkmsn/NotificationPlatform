import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, generateNotificationPayload } from '../config.js';

export const faultSentCounter = new Counter('fault_notifications_sent_total');

export const options = {
  scenarios: {
    fault_and_dlq_lifecycle: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 1,
      maxDuration: '1m',
    },
  },
};

const jsonParams = {
  headers: {
    'Content-Type': 'application/json',
  },
};

export default function () {
  console.log('--- BƯỚC 1: Cấu hình Downstream Provider gặp lỗi 500 ---');
  let res = http.post(`${BASE_URL}/api/v1/circuit-breaker/simulate-mode?mode=SERVER_ERROR_500`);
  check(res, { 'Cấu hình mode 500 thành công': (r) => r.status === 200 });

  console.log('--- BƯỚC 2: Gửi 20 tin nhắn lỗi để kích hoạt Circuit Breaker và Retry ---');
  for (let i = 1; i <= 20; i++) {
    const payload = JSON.stringify(generateNotificationPayload({
      channel: 'EMAIL',
      priority: 'HIGH',
      recipient: `fault_candidate_${i}@example.com`,
      subject: `[DLQ Test Candidate #${i}] Invoice notification`
    }));

    res = http.post(`${BASE_URL}/api/v1/notifications`, payload, jsonParams);
    check(res, { '202 Accepted': (r) => r.status === 202 });
    faultSentCounter.add(1);
    sleep(0.05);
  }

  console.log('--- BƯỚC 3: Chờ 8 giây để Worker retry và Circuit Breaker trip OPEN... ---');
  sleep(8);

  console.log('--- BƯỚC 4: Kiểm tra trạng thái Circuit Breaker ---');
  res = http.get(`${BASE_URL}/api/v1/circuit-breaker`);
  check(res, {
    'Lấy trạng thái Circuit Breaker thành công': (r) => r.status === 200,
    'Circuit Breaker đã TRIP sang OPEN': (r) => {
      const data = JSON.parse(r.body);
      console.log(`Current CB State: ${data.mockProviderState}`);
      return data.mockProviderState === 'OPEN' || data.mockProviderState === 'HALF_OPEN';
    }
  });

  console.log('--- BƯỚC 5: Kiểm tra danh sách Dead Letter Queue (DLQ) ---');
  res = http.get(`${BASE_URL}/api/v1/dlq?page=0&size=10`);
  check(res, {
    'Lấy danh sách DLQ thành công': (r) => r.status === 200,
    'Có bản ghi trong DLQ': (r) => {
      const data = JSON.parse(r.body);
      console.log(`DLQ Total Elements: ${data.totalElements}`);
      return data.totalElements > 0;
    }
  });

  console.log('--- BƯỚC 6: Khôi phục Provider về SUCCESS và Reset Circuit Breaker ---');
  http.post(`${BASE_URL}/api/v1/circuit-breaker/simulate-mode?mode=SUCCESS`);
  http.post(`${BASE_URL}/api/v1/circuit-breaker/reset`);
  console.log('Hoàn tất kịch bản kiểm thử DLQ & Resilience!');
}
