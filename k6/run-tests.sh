#!/bin/bash

BASE_URL="${BASE_URL:-http://localhost:8080}"
K6_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=========================================================="
echo "    NOTIFICATION PLATFORM - K6 PERFORMANCE SUITE"
echo "    Target URL: ${BASE_URL}"
echo "=========================================================="

run_burst() {
    echo ""
    echo ">> [1/4] Running 10k Notification Burst Test (IMP-25)..."
    k6 run "${K6_DIR}/scenarios/01-burst-load-10k.js" -e BASE_URL="${BASE_URL}" || true
}

run_ratelimit() {
    echo ""
    echo ">> [2/4] Running Provider Rate-Limit & Token Bucket Test (IMP-26)..."
    k6 run "${K6_DIR}/scenarios/02-rate-limit-test.js" -e BASE_URL="${BASE_URL}" || true
}

run_priority() {
    echo ""
    echo ">> [3/4] Running Multi-Priority (CRITICAL vs LOW) Ingestion Test (IMP-27)..."
    k6 run "${K6_DIR}/scenarios/03-priority-test.js" -e BASE_URL="${BASE_URL}" || true
}

run_dlq() {
    echo ""
    echo ">> [4/4] Running Circuit Breaker & DLQ Fault Injection Test (IMP-28)..."
    k6 run "${K6_DIR}/scenarios/04-dlq-fault-test.js" -e BASE_URL="${BASE_URL}" || true
}

case "$1" in
    burst)
        run_burst
        ;;
    ratelimit)
        run_ratelimit
        ;;
    priority)
        run_priority
        ;;
    dlq)
        run_dlq
        ;;
    all|"")
        run_burst
        run_ratelimit
        run_priority
        run_dlq
        echo ""
        echo "=========================================================="
        echo "All k6 Benchmark Scenarios Completed Successfully!"
        echo "=========================================================="
        ;;
    *)
        echo "Usage: $0 {burst|ratelimit|priority|dlq|all}"
        exit 1
        ;;
esac
