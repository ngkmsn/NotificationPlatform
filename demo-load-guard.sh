#!/usr/bin/env bash
# ==============================================================================
# Notification Platform - Load Guard & Cutoff Demonstration Script
# Kịch bản demo luồng chịu tải: Normal -> Warning -> Throttle (85%) -> Cutoff (95%) -> Phục hồi
# ==============================================================================

BASE_URL="http://127.0.0.1:8080"
GREEN="\033[0;32m"
YELLOW="\033[1;33m"
ORANGE="\033[0;33m"
RED="\033[0;31m"
CYAN="\033[0;36m"
BOLD="\033[1m"
NC="\033[0m"

echo -e "${BOLD}${CYAN}======================================================================${NC}"
echo -e "${BOLD}${CYAN} 🚀 DEMO LUỒNG CHỊU TẢI & TỰ NGẮT BẢO VỆ HỆ THỐNG (SYSTEM LOAD GUARD) ${NC}"
echo -e "${BOLD}${CYAN}======================================================================${NC}\n"

# 0. Kiểm tra Quarkus Backend
echo -e "🔍 Đang kiểm tra trạng thái Backend tại ${BASE_URL}..."
if ! curl -s -f "${BASE_URL}/q/health" > /dev/null 2>&1; then
    echo -e "${RED}❌ Không thể kết nối tới Backend! Vui lòng khởi động backend bằng lệnh:${NC}"
    echo -e "${YELLOW}   mvn quarkus:dev${NC}\n"
    exit 1
fi
echo -e "${GREEN}✅ Backend đang hoạt động bình thường!${NC}\n"

send_notification() {
    local priority=$1
    local subject=$2
    local recipient="test-user@example.com"

    echo -n "   👉 Gửi tin nhắn Priority=[$priority]: "
    local response=$(curl -s -w "\nHTTP_STATUS:%{http_code}\n" -X POST "${BASE_URL}/api/v1/notifications" \
        -H "Content-Type: application/json" \
        -d "{
            \"recipient\": \"${recipient}\",
            \"channel\": \"EMAIL\",
            \"priority\": \"${priority}\",
            \"subject\": \"${subject}\",
            \"content\": \"Nội dung kiểm thử luồng chịu tải\"
        }")

    local http_code=$(echo "$response" | grep "HTTP_STATUS" | cut -d':' -f2)
    local body=$(echo "$response" | grep -v "HTTP_STATUS")

    if [ "$http_code" -eq 202 ]; then
        echo -e "${GREEN}HTTP 202 ACCEPTED (Được tiếp nhận thành công)${NC}"
    elif [ "$http_code" -eq 429 ]; then
        echo -e "${ORANGE}HTTP 429 TOO MANY REQUESTS (Bị từ chối cắt giảm tải)${NC}"
        echo -e "      Phản hồi: $body"
    elif [ "$http_code" -eq 503 ]; then
        echo -e "${RED}HTTP 503 SERVICE UNAVAILABLE (Ngắt khẩn cấp - Hard Cutoff)${NC}"
        echo -e "      Phản hồi: $body"
    else
        echo -e "${YELLOW}HTTP $http_code${NC} - $body"
    fi
}

# ==============================================================================
# PHA 1: Trạng thái bình thường (NORMAL < 70%)
# ==============================================================================
echo -e "${BOLD}1. [PHA 1 - NORMAL < 70%]: Vận hành bình thường (Mô phỏng 35% CPU, 40% RAM)${NC}"
curl -s -X POST "${BASE_URL}/api/v1/dashboard/simulate-load?enabled=true&cpu=35.0&ram=40.0" > /dev/null
sleep 1
send_notification "LOW" "Tin khuyến mãi hàng tuần"
send_notification "CRITICAL" "Mã xác thực OTP đăng nhập"
echo ""

# ==============================================================================
# PHA 2: Trạng thái chớm tải cao (WARNING 78%)
# ==============================================================================
echo -e "${BOLD}2. [PHA 2 - WARNING 78%]: Chớm cao nhưng chưa đến ngưỡng ngắt${NC}"
echo -e "   ⚡ Kích hoạt mô phỏng: CPU = 78%, RAM = 65%..."
curl -s -X POST "${BASE_URL}/api/v1/dashboard/simulate-load?enabled=true&cpu=78.0&ram=65.0" > /dev/null
sleep 2

send_notification "LOW" "Tin tức sự kiện (LOW)"
send_notification "CRITICAL" "Mã OTP giao dịch (CRITICAL)"
echo -e "   ℹ️ ${YELLOW}Ghi chú: Tại mức WARNING (75%-84%), hệ thống chỉ cảnh báo log/metric, vẫn tiếp nhận 100% traffic.${NC}\n"

# ==============================================================================
# PHA 3: Ngưỡng quá tải chọn lọc (THROTTLED >= 85%)
# ==============================================================================
echo -e "${BOLD}3. [PHA 3 - THROTTLED 88%]: Chạm ngưỡng 85% -> Kích hoạt Adaptive Load Shedding${NC}"
echo -e "   ⚡ Kích hoạt mô phỏng: CPU = 88%, RAM = 75%..."
curl -s -X POST "${BASE_URL}/api/v1/dashboard/simulate-load?enabled=true&cpu=88.0&ram=75.0" > /dev/null
sleep 2

echo -e "   🔔 ${ORANGE}Hệ thống tự động bắn Push Notification cảnh báo tới Admin: '🚨 CẢNH BÁO QUÁ TẢI (88% CPU)'${NC}"
send_notification "LOW" "Bản tin quảng cáo (LOW)"
send_notification "NORMAL" "Nhắc nhở cập nhật hồ sơ (NORMAL)"
send_notification "CRITICAL" "Mã OTP rút tiền (CRITICAL)"
echo -e "   ℹ️ ${GREEN}Quan sát: Tin LOW/NORMAL bị chặn (429), nhưng tin CRITICAL vẫn lọt qua an toàn!${NC}\n"

# ==============================================================================
# PHA 4: Ngưỡng ngắt khẩn cấp (CRITICAL_CUTOFF >= 95%)
# ==============================================================================
echo -e "${BOLD}4. [PHA 4 - CRITICAL_CUTOFF 97%]: Chạm ngưỡng 95% -> Kích hoạt Emergency Hard Cutoff${NC}"
echo -e "   🚨 Kích hoạt mô phỏng: CPU = 97%, RAM = 92%..."
curl -s -X POST "${BASE_URL}/api/v1/dashboard/simulate-load?enabled=true&cpu=97.0&ram=92.0" > /dev/null
sleep 2

echo -e "   🔔 ${RED}Hệ thống tự động bắn Push Notification khẩn cấp: '🚨 NGẮT KHẨN CẤP (97% CPU)'${NC}"
echo -e "   🛑 ${RED}Background Outbox Publisher lập tức bị đóng băng (Pause) không quét DB để hạ nhiệt.${NC}"
send_notification "LOW" "Bản tin quảng cáo (LOW)"
send_notification "CRITICAL" "Mã OTP rút tiền (CRITICAL)"
echo -e "   ℹ️ ${RED}Quan sát: 100% traffic kể cả CRITICAL đều bị từ chối với HTTP 503 (Retry-After: 60s) để ngăn ngừa sập máy chủ!${NC}\n"

# ==============================================================================
# PHA 5: Phục hồi an toàn (HYSTERESIS RECOVERY)
# ==============================================================================
echo -e "${BOLD}5. [PHA 5 - HYSTERESIS RECOVERY]: Hạ nhiệt về mức an toàn (CPU 35%, RAM 40%)${NC}"
echo -e "   🔄 Đưa tải về 35% CPU..."
curl -s -X POST "${BASE_URL}/api/v1/dashboard/simulate-load?enabled=true&cpu=35.0&ram=40.0" > /dev/null
curl -s -X POST "${BASE_URL}/api/v1/dashboard/simulate-load?enabled=false" > /dev/null

echo -e "   ⏳ Chu kỳ 1 (5s): Đang theo dõi an toàn... (Hệ thống vẫn giữ Throttle để chống dập dờn)"
sleep 6
send_notification "LOW" "Kiểm tra chu kỳ 1"

echo -e "   ⏳ Chu kỳ 2 (5s): Đạt đủ 2 chu kỳ an toàn liên tiếp (<70%) -> Hệ thống chính thức phục hồi về NORMAL!"
sleep 6
echo -e "   🔔 ${GREEN}Hệ thống bắn Push Notification phục hồi: '✅ HỆ THỐNG PHỤC HỒI'${NC}"
send_notification "LOW" "Gửi lại tin LOW sau khi bình thường"
send_notification "CRITICAL" "Gửi lại tin CRITICAL sau khi bình thường"

echo -e "\n${BOLD}${CYAN}======================================================================${NC}"
echo -e "${BOLD}${GREEN} 🎉 DEMO HOÀN TẤT THÀNH CÔNG!${NC}"
echo -e "${BOLD}${CYAN}======================================================================${NC}"
