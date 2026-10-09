#!/usr/bin/env bash
# The "status" window of scripts/dev-up.sh: every port, every 3 seconds. Green = answering.
cd "$(dirname "${BASH_SOURCE[0]}")/.."; set -a; . ./local.env; set +a
row(){ local n="$1" p="$2" kind="${3:-http}"
  if (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null; then printf '  \033[32m● %-14s\033[0m %-6s up\n' "$n" "$p"; else printf '  \033[31m○ %-14s\033[0m %-6s down\n' "$n" "$p"; fi; }
while true; do clear
  echo "Ensuretech: processes (this window refreshes every 3 s)"; echo
  echo " core"; row identity 8000; row organization 8081; row policy 8082; row "policy gRPC" 9090; row billing 8084; row "billing gRPC" 9091; row engine 8083; row integration 8085; row read-service 8087; row sheet-generator 8086
  echo; echo " celery (engine)"; row "Flower" 5555; row "RabbitMQ UI" 15672
  echo; echo " front door"; row "BFF + portal" 8090
  echo; echo " infrastructure"; row PostgreSQL "$DB_PORT"; row Redis 6379; row "RabbitMQ" 5672; row Kafka 9092; row MongoDB 27017; row "SMTP/Mailpit" 1025
  echo; echo " observability (optional, run.html section 16)"; row Prometheus 9099; row Grafana 3000; row "Kafka UI" 8088
  echo; echo "Portal: http://localhost:8090/login   |   Ctrl-b w = list windows   |   Ctrl-b d = detach   |   scripts/dev-down.sh = stop everything"
  sleep 3; done
