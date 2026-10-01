# Performance & Benchmark Testing Suite (k6)

Tài liệu hướng dẫn thực thi và ghi nhận kết quả kiểm thử hiệu năng cho **Notification Platform** sử dụng [k6](https://k6.io/).

---

## 1. Yêu cầu môi trường & Chuẩn bị

1. **Khởi động Infrastructure Services**:
   ```bash
   docker compose up -d
   ```
2. **Khởi động Ứng dụng Backend**:
   ```bash
   mvn quarkus:dev
   ```
   *(Backend lắng nghe tại `http://localhost:8080`, Prometheus tại `http://localhost:9090`, Grafana tại `http://localhost:3000`)*

---

## 2. Các Kịch bản Kiểm thử Tải (k6 Scenarios)

### Kịch bản 1: 10,000 Notification Burst Load (IMP-25)
* **Mục tiêu**: Đo lường khả năng tiếp nhận tải dồn dập vào Transactional Outbox.
* **Cấu hình**: 50 Virtual Users (VU), 10.000 requests.
* **Tiêu chí đạt (SLA Thresholds)**:
  - `http_req_failed`: `< 1%`
  - `http_req_duration (p95)`: `< 200 ms`
* **Lệnh chạy**:
  ```bash
  k6 run k6/scenarios/01-burst-load-10k.js
  ```

---

### Kịch bản 2: Provider Rate-Limiting & Token Bucket Stress Test (IMP-26)
* **Mục tiêu**: Kiểm tra cơ chế điều tiết tải Token Bucket trên Redis khi lưu lượng vượt quá 100 req/s.
* **Cấu hình**: 150 req/s liên tục trong 45 giây.
* **Tiêu chí đạt**:
  - API duy trì phản hồi `202 ACCEPTED` mà không bị sập.
  - Worker tự động xếp hàng và giãn cách gửi qua downstream provider đúng mức quy định (20 req/s refill rate / 100 capacity).
  - Metric `notifications_throttled_total` tăng tương ứng với số tin bị throttle.
* **Lệnh chạy**:
  ```bash
  k6 run k6/scenarios/02-rate-limit-test.js
  ```

---

### Kịch bản 3: Multi-Priority Ingestion & Processing (CRITICAL vs LOW) (IMP-27)
* **Mục tiêu**: Kiểm chứng thông báo mức `CRITICAL` (OTP, cảnh báo bảo mật) được ưu tiên xử lý trước so với `LOW` (báo cáo, tin khuyến mãi).
* **Cấu hình**:
  - Giai đoạn 1: Bơm 3.000 tin `LOW` priority vào queue.
  - Giai đoạn 2: Bơm 300 tin `CRITICAL` priority vào sau 5 giây.
* **Tiêu chí đạt**:
  - Tin `CRITICAL` được tiêu thụ và gửi thành công với độ trễ (Time-to-Deliver) thấp hơn nhiều so với tin `LOW`.
* **Lệnh chạy**:
  ```bash
  k6 run k6/scenarios/03-priority-test.js
  ```

---

### Kịch bản 4: Circuit Breaker & DLQ Fault Injection (IMP-28)
* **Mục tiêu**: Kiểm tra tự động hóa ngắt mạch (Circuit Breaker) và cách ly lỗi vào Dead Letter Queue khi downstream gặp sự cố HTTP 500.
* **Quy trình test**:
  1. Bật giả lập lỗi HTTP 500 trên provider.
  2. Bơm 20 tin nhắn qua cổng lỗi.
  3. Kiểm tra Circuit Breaker tự động chuyển sang `OPEN` (Fail-fast 0ms).
  4. Xác nhận toàn bộ 20 tin được chuyển vào bảng `notifications` trạng thái `DEAD_LETTER` (DLQ).
  5. Khôi phục trạng thái và reset Circuit Breaker.
* **Lệnh chạy**:
  ```bash
  k6 run k6/scenarios/04-dlq-fault-test.js
  ```

---

## 3. Chạy Toàn Bộ Test Suite (IMP-29)

Chạy nhanh toàn bộ các kịch bản với script:
```bash
./k6/run-tests.sh all
```
Hoặc từng kịch bản riêng lẻ:
```bash
./k6/run-tests.sh burst       # Chạy 10k burst
./k6/run-tests.sh ratelimit   # Chạy rate limit test
./k6/run-tests.sh priority    # Chạy priority test
./k6/run-tests.sh dlq         # Chạy DLQ & Circuit Breaker test
```
