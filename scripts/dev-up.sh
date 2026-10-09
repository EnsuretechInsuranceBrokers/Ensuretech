#!/usr/bin/env bash
# Start the whole Ensuretech stack for local QA, one tmux window per process.
#
#   scripts/dev-up.sh                 start everything that can run, then attach
#   scripts/dev-up.sh --check         only check the laptop, the infrastructure and the databases; start nothing
#   scripts/dev-up.sh --no-kafka      no cross-service events (Kafka is not running): the portal works, dashboards and policy-to-billing hand-offs do not
#   scripts/dev-up.sh --with-mail     also the notification consumer and sender
#   scripts/dev-up.sh --with-sheet    also sheet-generator-service
#   scripts/dev-up.sh --skip read,notification     leave named components out   (names: admin identity org policy billing engine worker-ingest worker-validation worker-computation beat flower integration read projector bff notify-consumer notify-sender sheet)
#   scripts/dev-up.sh --only identity,engine,integration,bff   start only these
#   scripts/dev-up.sh --devtools      also Django Silk + Debug Toolbar on identity, engine, read-service, notification (http://127.0.0.1:PORT/silk/); needs DEBUG, laptop only
#   scripts/dev-up.sh --ray           send debug output to the Ray desktop app (myray.app): BFF requests, calls to core services, and every SQL query of the Django services
#   scripts/dev-up.sh --eager         the old shortcut: Celery tasks run inline in the engine's web process (no broker, no workers, no Flower)
#   scripts/dev-up.sh --no-admin      do not start the operations console (http://127.0.0.1:8100: Spring Boot Admin + Jobs)
#   scripts/dev-up.sh --no-flower     do not start the Flower dashboard (http://127.0.0.1:5555)
#   scripts/dev-up.sh --no-telescope  leave Laravel Telescope (http://localhost:8090/telescope) off
#   scripts/dev-up.sh --rebuild       rebuild the Spring jars even if they exist
#   scripts/dev-up.sh --no-attach     start and return (CI, or if you attach later with: tmux attach -t ensuretech)
#   DB_PORT=5433 scripts/dev-up.sh    use the second (test) PostgreSQL server (run.html section 2)
#
# Stop it with scripts/dev-down.sh. Needs bash, tmux, and the tools in run.html section 1. Infrastructure (PostgreSQL, Redis, Kafka, MongoDB) must already be running.
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; cd "$ROOT"
SESSION="${ENSURETECH_TMUX_SESSION:-ensuretech}"
DEV="$ROOT/.dev"; mkdir -p "$DEV/logs" "$DEV/run"
EAGER=0; FLOWER=1; ADMIN=1; TELESCOPE=1; DEVTOOLS=0; RAY=0; CHECK=0; NO_KAFKA=0; REBUILD=0; ATTACH=1; WITH_MAIL=0; WITH_SHEET=0; SKIP=","; ONLY=""
while [ $# -gt 0 ]; do case "$1" in
  --check) CHECK=1;; --eager) EAGER=1;; --no-flower) FLOWER=0;; --no-admin) ADMIN=0;; --no-telescope) TELESCOPE=0;; --devtools) DEVTOOLS=1;; --ray) RAY=1;; --no-kafka) NO_KAFKA=1;; --rebuild) REBUILD=1;; --no-attach) ATTACH=0;; --with-mail) WITH_MAIL=1;; --with-sheet) WITH_SHEET=1;;
  --skip) shift; SKIP=",$1,";; --only) shift; ONLY=",$1,";; -h|--help) sed -n '2,17p' "$0"; exit 0;;
  *) echo "unknown option: $1 (try --help)"; exit 2;; esac; shift; done

red(){ printf '\033[31m%s\033[0m\n' "$*"; }; grn(){ printf '\033[32m%s\033[0m\n' "$*"; }; ylw(){ printf '\033[33m%s\033[0m\n' "$*"; }
FAIL=0; bad(){ red "  ✗ $*"; FAIL=1; }; ok(){ grn "  ✓ $*"; }; warn(){ ylw "  ! $*"; }

[ -f local.env ] || { red "local.env not found: run this from a full checkout"; exit 1; }
set -a; . ./local.env; set +a
tcp(){ (exec 3<>"/dev/tcp/${2:-127.0.0.1}/$1") 2>/dev/null; }
want(){ [ -n "$ONLY" ] && { case "$ONLY" in *",$1,"*) return 0;; *) return 1;; esac; }; case "$SKIP" in *",$1,"*) return 1;; esac; return 0; }
need(){ command -v "$1" >/dev/null 2>&1 && ok "$1" || bad "$1 not found: $2"; }

echo "1. Tools"
need tmux "install tmux (brew install tmux / apt install tmux)"; need curl "install curl"; need psql "install the PostgreSQL client"; need openssl "install openssl"
need java "install JDK 21"; need mvn "install Maven 3.9+"; need php "install PHP 8.3"; need composer "install Composer 2"
command -v python3.13 >/dev/null 2>&1 && ok "python3.13" || bad "python3.13 not found (3.13 is required)"
java -version 2>&1 | grep -q '"21' && ok "JDK 21" || warn "java is not version 21 (the Spring services target 21)"

echo "2. Infrastructure"
tcp "$DB_PORT" "$DB_HOST" && ok "PostgreSQL on $DB_HOST:$DB_PORT" || bad "PostgreSQL is not answering on $DB_HOST:$DB_PORT"
tcp "$REDIS_PORT" && ok "Redis on $REDIS_PORT" || { want policy && bad "Redis is not running on $REDIS_PORT (policy-service needs it)"; }
KAFKA_UP=0; tcp 9092 && { KAFKA_UP=1; ok "Kafka on 9092"; } || { [ $NO_KAFKA = 1 ] && warn "Kafka is not running: starting without events (--no-kafka)" || bad "Kafka is not running on 9092 (start it, or use --no-kafka)"; }
MONGO_UP=0; tcp 27017 && { MONGO_UP=1; ok "MongoDB/FerretDB on 27017"; } || { warn "no MongoDB/FerretDB on 27017: read-service and its projector are left out (dashboards stay empty)"; SKIP="$SKIP,read,projector,"; }
BROKER=none
if [ $EAGER = 0 ] && { want engine || want worker-computation; }; then
  if tcp 5672; then BROKER=rabbitmq; ok "RabbitMQ on 5672: the Celery broker the engine is designed for"; [ "$CELERY_BROKER_URL" = "redis://localhost:6379/1" ] && export CELERY_BROKER_URL="amqp://guest:guest@localhost:5672//"
  elif tcp "$REDIS_PORT"; then BROKER=redis; warn "no RabbitMQ on 5672: using Redis as the Celery broker. The pipeline runs, but the dead-letter queues and queue priorities (RabbitMQ features) do not exist."
  else bad "no Celery broker: start RabbitMQ (5672) or Redis, or use --eager (tasks run inline, no workers)"; fi
fi
tcp 1025 && ok "SMTP (Mailpit) on 1025" || { [ $WITH_MAIL = 1 ] && warn "nothing is listening on SMTP 1025: emails will fail to send"; }

echo "3. Databases (built by  \\i setup.sql )"
for d in identity org policy billing endorsement integration; do
  PGPASSWORD="$DATABASE_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DATABASE_USER" -d "ensuretech_$d" -Atc 'select 1' >/dev/null 2>&1 && ok "ensuretech_$d" || bad "cannot open ensuretech_$d as $DATABASE_USER: run  \\i setup.sql  as the postgres superuser (run.html section 2)"
done
[ -f services/identity-service/keys/jwt-private.pem ] && ok "signing keys" || { warn "creating the identity signing keys"; mkdir -p services/identity-service/keys
  openssl genrsa -out services/identity-service/keys/jwt-private.pem 2048 2>/dev/null && openssl rsa -in services/identity-service/keys/jwt-private.pem -pubout -out services/identity-service/keys/jwt-public.pem 2>/dev/null && ok "signing keys created"; }
[ "${#GATEWAY_SECRET}" -ge 32 ] && ok "GATEWAY_SECRET set (${#GATEWAY_SECRET} characters)" || bad "GATEWAY_SECRET missing or short in local.env"
[ -n "${ENGINE_CLIENT_ID:-}" ] && ok "service account in local.env" || bad "ENGINE_CLIENT_ID is empty in local.env (run.html section 8.1)"

[ $CHECK = 1 ] && { [ $FAIL = 0 ] && { grn "Everything needed is in place."; exit 0; } || { red "Fix the ✗ lines above, then run again."; exit 1; }; }
[ $FAIL = 0 ] || { red "Not starting: fix the ✗ lines above (or run with --check to see them again)."; exit 1; }
tmux has-session -t "$SESSION" 2>/dev/null && { red "tmux session '$SESSION' already exists. Stop it with scripts/dev-down.sh, or attach: tmux attach -t $SESSION"; exit 1; }
for p in 8000 8081 8082 8083 8084 8085 8086 8087 8090 9090 9091 5555 8100; do tcp "$p" && { red "port $p is already in use (a previous run? scripts/dev-down.sh)"; exit 1; }; done

# ── 4. prepare (first run is slow: dependencies, builds, migrations) ─────────────────────────────────────────────────────────
echo "4. Preparing (first run downloads dependencies; output in .dev/logs/prepare-*.log)"
prep(){ local name="$1"; shift; if "$@" >"$DEV/logs/prepare-$name.log" 2>&1; then ok "$name"; else bad "$name failed: see .dev/logs/prepare-$name.log"; fi; }
venv(){ local svc="$1" extra="${2:-}"; local d="services/$svc"
  [ -x "$d/.venv/bin/python" ] || python3.13 -m venv "$d/.venv" || return 1
  ( cd "$d" && .venv/bin/python ../../scripts/install_python_deps.py $([ $DEVTOOLS = 1 ] && echo --devtools) && { [ -z "$extra" ] || .venv/bin/pip install $extra; } ); }
for s in organization policy billing integration sheet-generator admin; do
  case "$s" in organization) n=org;; sheet-generator) n=sheet;; *) n=$s;; esac
  want "$n" || continue; [ "$s" = sheet-generator ] && [ $WITH_SHEET = 0 ] && continue; [ "$s" = admin ] && [ $ADMIN = 0 ] && continue
  jar="services/$s-service/target/$s-service-0.1.0.jar"
  stale=0; [ -f "$jar" ] && [ -n "$(find "services/$s-service/src" "services/$s-service/pom.xml" -newer "$jar" -type f 2>/dev/null | head -1)" ] && stale=1
  if [ $REBUILD = 1 ] || [ ! -f "$jar" ] || [ $stale = 1 ]; then prep "build-$s" bash -c "cd services/$s-service && mvn -q -DskipTests package"; else ok "build-$s (jar is up to date)"; fi
done
if want identity; then prep venv-identity venv identity-service; prep migrate-identity bash -c "cd services/identity-service && DATABASE_URL='$IDENTITY_DB' .venv/bin/python manage.py migrate -v0"; fi
silk(){ [ $DEVTOOLS = 1 ] && prep "silk-$1" bash -c "cd services/$1-service && DEV_TOOLS=true DEBUG=true DATABASE_URL='$2' .venv/bin/python manage.py migrate silk --database=devtools -v0"; }
[ $DEVTOOLS = 1 ] && want identity && silk identity "$IDENTITY_DB"
if want engine || want relay; then prep venv-engine venv endorsement-service uvicorn
  prep genproto-engine bash -c "cd services/endorsement-service && .venv/bin/python manage.py genproto"
  prep migrate-engine bash -c "cd services/endorsement-service && DATABASE_URL='$ENDORSEMENT_DB' .venv/bin/python manage.py migrate -v0"; silk endorsement "$ENDORSEMENT_DB"; fi
if want read || want projector; then prep venv-read venv read-service; silk read "$ENDORSEMENT_DB"; fi
if [ $WITH_MAIL = 1 ] && { want notify-consumer || want notify-sender; }; then prep venv-notification venv notification-service; prep migrate-notification bash -c "cd services/notification-service && DATABASE_URL='$NOTIFICATION_DB' .venv/bin/python manage.py migrate -v0"; silk notification "$NOTIFICATION_DB"; fi
if want bff; then
  [ -d services/bff/vendor ] || prep composer-bff bash -c "cd services/bff && composer install --no-interaction"
  [ -f services/bff/.env ] || prep env-bff bash -c "cd services/bff && cp .env.example .env && php artisan key:generate"
  [ $TELESCOPE = 1 ] && prep telescope-store bash -c "cd services/bff && TELESCOPE_ENABLED=true APP_ENV=local php artisan migrate --database=telescope --path=database/migrations --force"
fi
[ $FAIL = 0 ] || { red "Preparation failed; nothing was started."; exit 1; }
if [ $KAFKA_UP = 1 ]; then
  KT="$(command -v kafka-topics.sh || ls "${KAFKA_HOME:-/nonexistent}"/bin/kafka-topics.sh 2>/dev/null | head -1)"
  if [ -n "$KT" ]; then for t in employer.events policy.events endorsement.events cd-balance.updates tpa.events; do "$KT" --bootstrap-server localhost:9092 --create --if-not-exists --topic "$t" --partitions 1 --replication-factor 1 >/dev/null 2>&1; done; ok "Kafka topics"
  elif command -v docker >/dev/null 2>&1 && docker compose -f docker-compose.infra.yml ps kafka 2>/dev/null | grep -q kafka; then
    for t in employer.events policy.events endorsement.events cd-balance.updates tpa.events; do docker compose -f docker-compose.infra.yml exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic "$t" --partitions 1 --replication-factor 1 >/dev/null 2>&1; done; ok "Kafka topics (via docker)"
  else warn "could not find kafka-topics.sh: create the five topics yourself (run.html section 5) before the read-service projector starts"; fi
fi

# ── 5. one tmux window per process ───────────────────────────────────────────────────────────────────────────────────────────
SP_KAFKA=""; [ $NO_KAFKA = 1 ] && SP_KAFKA="--app.kafka.listener.enabled=false --app.outbox.relay.enabled=false"
mk(){ # name, directory, command   -> .dev/run/name.sh
  local f="$DEV/run/$1.sh"
  { echo '#!/usr/bin/env bash'; echo "cd '$ROOT/$2' || exit 1"; echo "cd '$ROOT'; set -a; . ./local.env; set +a; cd '$ROOT/$2'"; echo "echo \"[\$(date +%T)] $1: starting\""
    echo "$3 2>&1 | tee -a '$DEV/logs/$1.log'"; echo "echo; echo \"[\$(date +%T)] $1 exited. Scroll up for the reason; Enter closes this window.\"; read"; } >"$f"; chmod +x "$f"; }
add(){ tmux new-window -t "$SESSION" -n "$1" "bash '$DEV/run/$1.sh'"; }
tmux new-session -d -s "$SESSION" -n status -x 200 -y 50 "bash '$ROOT/scripts/dev-status.sh'"
tmux set-option -t "$SESSION" -g remain-on-exit off; tmux set-option -t "$SESSION" -g mouse on; tmux set-option -t "$SESSION" -g history-limit 20000

DJ=""; [ $DEVTOOLS = 1 ] && DJ="DEV_TOOLS=true DEBUG=true "; [ $RAY = 1 ] && DJ="${DJ}RAY_QUERIES=true "
spring(){ # name dir jar port extra-env
  mk "$1" "services/$2" "$([ $ADMIN = 1 ] && echo ADMIN_CLIENT_ENABLED=true) $5 java -jar target/$2-0.1.0.jar $SP_KAFKA"; }
want identity && { mk identity services/identity-service "${DJ}DATABASE_URL='$IDENTITY_DB' JWT_PRIVATE_KEY_PATH=keys/jwt-private.pem JWT_PUBLIC_KEY_PATH=keys/jwt-public.pem .venv/bin/python manage.py runserver 127.0.0.1:8000 --noreload"; add identity
  echo -n "  waiting for identity "; for i in $(seq 60); do curl -s -o /dev/null localhost:8000/api/v1/auth/me/ && break; sleep 1; echo -n .; done; echo; }
want org && { spring org organization-service x 8081 "DATABASE_URL='$ORG_JDBC' PORT=8081"; add org; }
want policy && { spring policy policy-service x 8082 "DATABASE_URL='$POLICY_JDBC' PORT=8082 GRPC_PORT=9090"; add policy; }
want billing && { spring billing billing-service x 8084 "DATABASE_URL='$BILLING_JDBC' PORT=8084 GRPC_PORT=9091"; add billing; }
ENG_ENV="DATABASE_URL='$ENDORSEMENT_DB' SERVICE_CLIENT_ID='$ENGINE_CLIENT_ID' SERVICE_CLIENT_SECRET='$ENGINE_CLIENT_SECRET' CELERY_BROKER_URL='$CELERY_BROKER_URL'"
[ $NO_KAFKA = 1 ] && ENG_ENV="$ENG_ENV RELAY_OUTBOX=false"
[ $EAGER = 1 ] && ENG_ENV="$ENG_ENV CELERY_TASK_ALWAYS_EAGER=true"
want engine && { mk engine services/endorsement-service "${DJ}$ENG_ENV .venv/bin/uvicorn config.asgi:application --host 127.0.0.1 --port 8083"; add engine; }
if [ $EAGER = 1 ]; then
  [ $NO_KAFKA = 0 ] && want relay && { mk relay services/endorsement-service "$ENG_ENV .venv/bin/python scripts/relay_loop.py"; add relay; }
else
  # Celery for real: three worker groups (the design's bulkheads: a person waiting on a screen never sits behind a 1M-row file), beat for the schedule, Flower to watch it
  wk(){ mk "$1" services/endorsement-service "${DJ}$ENG_ENV .venv/bin/celery -A config worker -n $2@%h -Q $3 -c ${4:-2} -l info"; add "$1"; }
  want worker-ingest && wk worker-ingest ingest csv.ingestion,gui.batch,maintenance 2
  want worker-validation && wk worker-validation validation validation.high,validation.standard 2
  want worker-computation && wk worker-computation computation computation 2
  want beat && { mk beat services/endorsement-service "$ENG_ENV .venv/bin/celery -A config beat -l info -s '$DEV/celerybeat-schedule'"; add beat; }
  if [ $FLOWER = 1 ] && want flower; then
    FL_API=""; [ "$BROKER" = rabbitmq ] && FL_API="--broker-api=http://guest:guest@localhost:15672/api/"
    mk flower services/endorsement-service "$ENG_ENV .venv/bin/celery -A config flower --address=127.0.0.1 --port=5555 --basic-auth='$FLOWER_BASIC_AUTH' --persistent=True --db='$DEV/flower.db' $FL_API"; add flower
  fi
fi
want admin && [ $ADMIN = 1 ] && { mk admin services/admin-service "SERVICE_CLIENT_ID='$ENGINE_CLIENT_ID' SERVICE_CLIENT_SECRET='$ENGINE_CLIENT_SECRET' PORT=8100 java -jar target/admin-service-0.1.0.jar"; add admin; }
want integration && { spring integration integration-service x 8085 "DATABASE_URL='$INTEGRATION_JDBC' PORT=8085 SERVICE_CLIENT_ID='$INTEGRATION_CLIENT_ID' SERVICE_CLIENT_SECRET='$INTEGRATION_CLIENT_SECRET'"; add integration; }
want read && { mk read services/read-service "${DJ}.venv/bin/python manage.py runserver 127.0.0.1:8087 --noreload"; add read; }
want projector && [ $NO_KAFKA = 0 ] && { mk projector services/read-service "${DJ}.venv/bin/python manage.py run_projector"; add projector; }
if [ $WITH_MAIL = 1 ]; then
  want notify-consumer && { mk notify-consumer services/notification-service "${DJ}DATABASE_URL='$NOTIFICATION_DB' .venv/bin/python manage.py run_consumer"; add notify-consumer; }
  want notify-sender && { mk notify-sender services/notification-service "${DJ}DATABASE_URL='$NOTIFICATION_DB' .venv/bin/python manage.py run_sender"; add notify-sender; }; fi
[ $WITH_SHEET = 1 ] && want sheet && { spring sheet sheet-generator-service x 8086 "DATABASE_URL='$SHEETGEN_JDBC' PORT=8086"; add sheet; }
want bff && { mk bff services/bff "SESSION_DRIVER=\${SESSION_DRIVER:-file} APP_ENV=local TELESCOPE_ENABLED=$([ $TELESCOPE = 1 ] && echo true || echo false) RAY_ENABLED=$([ $RAY = 1 ] && echo true || echo false) PHP_CLI_SERVER_WORKERS=4 php artisan serve --host 127.0.0.1 --port 8090"; add bff; }
tmux select-window -t "$SESSION:status"

echo; grn "Started in tmux session '$SESSION'."
echo "  Portal:      http://localhost:8090/login       (meera.kapoor@northwind.example / Str0ng-Pass-123!)"
[ $ADMIN = 1 ] && echo "  Console:     http://127.0.0.1:8100  (admin / $ADMIN_UI_PASSWORD)   Spring Boot Admin for every Spring service, and the Jobs page to stop rogue sync jobs"
[ $EAGER = 0 ] && [ $FLOWER = 1 ] && echo "  Flower:      http://127.0.0.1:5555  (login: $FLOWER_BASIC_AUTH)   Celery workers, queues, every task and its retries"
echo "  Windows:     Ctrl-b n / Ctrl-b p (next/previous), Ctrl-b w (list), mouse works. Detach: Ctrl-b d. Logs: .dev/logs/"
echo "  Spring services take 10-30 s to start; the 'status' window shows each one turn green."
[ $DEVTOOLS = 1 ] && echo "  Silk:        http://127.0.0.1:8000/silk/ (identity)  :8083/silk/ (engine)  :8087/silk/ (read)   Debug Toolbar appears on their HTML pages"
[ $RAY = 1 ] && echo "  Ray:         open the Ray app (myray.app) first; nothing is sent if it is not running"
[ $TELESCOPE = 1 ] && echo "  Telescope:   http://localhost:8090/telescope   (every request, every call to a core service, exceptions; local only)"
echo "  Metrics:     scripts/observability-up.sh  (Prometheus, Grafana, Kafka UI; docs/observability.md)"
echo "  Stop all:    scripts/dev-down.sh"
[ $ATTACH = 1 ] && [ -t 1 ] && exec tmux attach -t "$SESSION"
exit 0
