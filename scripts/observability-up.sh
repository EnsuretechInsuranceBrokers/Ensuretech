#!/usr/bin/env bash
# Start (or stop: scripts/observability-up.sh down) Prometheus, Grafana and Kafka UI. Development only; see docker-compose.observability.yml and docs/observability.md.
cd "$(dirname "${BASH_SOURCE[0]}")/.."; set -a; . ./local.env; set +a
[ "${1:-}" = down ] && exec docker compose -f docker-compose.observability.yml down
command -v docker >/dev/null || { echo "Docker is not installed. No-Docker route: docs/observability.md"; exit 1; }
printf '%s' "$METRICS_TOKEN" > observability/metrics-token; chmod 644 observability/metrics-token
printf '%s' "${FLOWER_BASIC_AUTH#*:}" > observability/flower-password; chmod 644 observability/flower-password
docker compose -f docker-compose.observability.yml up -d && cat <<EOT

  Grafana     http://localhost:3000   (admin / admin)  dashboard: Ensuretech services
  Prometheus  http://localhost:9099   (Status > Targets: the Spring services should be UP)
  Kafka UI    http://localhost:8088
  Flower      http://127.0.0.1:5555   (Celery: workers, queues, tasks; login in local.env FLOWER_BASIC_AUTH; started by scripts/dev-up.sh)
  Telescope   http://localhost:8090/telescope   (the BFF, started by scripts/dev-up.sh)
EOT
