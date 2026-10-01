#!/bin/bash
set -e

echo "=========================================================="
echo "🚀 Building Notification Platform Microservices Artifacts..."
echo "=========================================================="

mvn package -DskipTests

echo "=========================================================="
echo "🐳 Launching 3 Distributed Microservices..."
echo " - API Service (Port 8080)"
echo " - Outbox Publisher Service (Port 8082)"
echo " - Worker Consumer Service (Port 8083)"
echo "=========================================================="

docker compose -f docker-compose.microservices.yml up --build -d

echo ""
echo "=========================================================="
echo "✅ Microservices Deployment Finished!"
echo "=========================================================="
echo "🔗 Endpoints:"
echo " - API Service & Admin Console: http://localhost:8080/admin.html"
echo " - Client Demo App:            http://localhost:8080/client.html"
echo " - Outbox Publisher Health:     http://localhost:8082/q/health"
echo " - Worker Consumer Health:       http://localhost:8083/q/health"
echo " - Grafana Dashboard:           http://localhost:3000 (admin/admin)"
echo ""
echo "💡 To scale worker replicas:"
echo "   docker compose -f docker-compose.microservices.yml up -d --scale notification-worker=3"
echo "=========================================================="
