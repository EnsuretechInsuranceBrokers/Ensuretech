#!/usr/bin/env bash
# Run the BFF the way production should: nginx in front of PHP-FPM with OPcache, instead of `php artisan serve` (a single-purpose development server).
#
#   scripts/bff-fpm.sh                  nginx on 127.0.0.1:8090 -> PHP-FPM, static pool, OPcache + JIT
#   scripts/bff-fpm.sh --bench          also lift the BFF's rate limits (for qa/load/loadtest.py)
#   scripts/bff-fpm.sh --no-cache       skip Laravel's config and route cache (it is built at start and removed at exit; it freezes the environment, so a change to local.env needs a restart)
#   BFF_PORT=8090 BFF_FPM_WORKERS=8 scripts/bff-fpm.sh
#
# Needs php-fpm and nginx installed (Ubuntu: apt install php8.3-fpm nginx; stop the packaged services: they are not used, this script starts its own copies from .dev/fpm).
# It writes its config under .dev/fpm, reads local.env like every other launcher, and runs both processes in the foreground; Ctrl-C stops them.
# Why a pool of PHP workers: each PHP worker handles one request at a time, so the pool size is the BFF's concurrency. The BFF mostly waits on the core services, so
# 2 to 4 workers per CPU core is a good start; BFF_FPM_WORKERS overrides it. Ports 8090 must be free (stop the artisan-serve window first).
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; cd "$ROOT"
BENCH=0; CACHE=1
for a in "$@"; do case "$a" in --bench) BENCH=1;; --no-cache) CACHE=0;; esac; done
set -a; . ./local.env; set +a
FPM="$(command -v php-fpm8.3 || command -v php-fpm || ls /usr/sbin/php-fpm* 2>/dev/null | head -1)"; NGINX="$(command -v nginx || echo /usr/sbin/nginx)"
[ -x "$FPM" ] && [ -x "$NGINX" ] || { echo "php-fpm and nginx are needed (Ubuntu: sudo apt install php8.3-fpm nginx)"; exit 1; }
D="$ROOT/.dev/fpm"; mkdir -p "$D/tmp" "$D/run"; PORT="${BFF_PORT:-8090}"; WORKERS="${BFF_FPM_WORKERS:-$(( $(nproc) * 3 ))}"
if [ "$(id -u)" = 0 ]; then RUNAS="www-data"; chown -R www-data:www-data "$ROOT/services/bff/storage" "$ROOT/services/bff/bootstrap/cache" "$D"; else RUNAS="$(id -un)"; fi
if [ $BENCH = 1 ]; then export BFF_RATE_ANON=10000000 BFF_RATE_TENANT=10000000 BFF_RATE_USER=10000000 BFF_RATE_HEAVY=10000000; fi

cat > "$D/php.ini" <<EOF
memory_limit=256M
expose_php=0
variables_order=EGPCS
opcache.enable=1
opcache.memory_consumption=256
opcache.interned_strings_buffer=32
opcache.max_accelerated_files=20000
opcache.validate_timestamps=0
opcache.jit=tracing
opcache.jit_buffer_size=64M
realpath_cache_size=4096K
realpath_cache_ttl=600
EOF
cat > "$D/php-fpm.conf" <<EOF
[global]
pid = $D/run/php-fpm.pid
error_log = /proc/self/fd/2
daemonize = no
[bff]
user = $RUNAS
group = $(id -gn "$RUNAS")
listen = $D/run/php-fpm.sock
listen.owner = $RUNAS
listen.group = $(id -gn "$RUNAS")
listen.backlog = 1024
pm = static
pm.max_children = $WORKERS
pm.max_requests = 5000
clear_env = no
catch_workers_output = yes
decorate_workers_output = no
request_terminate_timeout = 120s
EOF
NGUSER=""; [ "$(id -u)" = 0 ] && NGUSER="user $RUNAS;"
cat > "$D/nginx.conf" <<EOF
$NGUSER
worker_processes auto;
pid $D/run/nginx.pid;
error_log stderr warn;
daemon off;
events { worker_connections 4096; }
http {
  access_log off;
  client_body_temp_path $D/tmp/body; proxy_temp_path $D/tmp/proxy; fastcgi_temp_path $D/tmp/fcgi; uwsgi_temp_path $D/tmp/uwsgi; scgi_temp_path $D/tmp/scgi;
  include /etc/nginx/mime.types;
  client_max_body_size 100m;
  server_tokens off;
  upstream fpm { server unix:$D/run/php-fpm.sock; }
  server {
    listen 127.0.0.1:$PORT backlog=1024;
    root $ROOT/services/bff/public;
    index index.php;
    location / { try_files \$uri /index.php?\$query_string; }
    location ~ \.php\$ {
      include /etc/nginx/fastcgi_params;
      fastcgi_param SCRIPT_FILENAME \$realpath_root\$fastcgi_script_name;
      fastcgi_param DOCUMENT_ROOT \$realpath_root;
      fastcgi_pass fpm;
      fastcgi_read_timeout 120s;
      fastcgi_request_buffering on;
    }
    location ~ /\.(?!well-known) { deny all; }
  }
}
EOF
cd "$ROOT/services/bff" || exit 1
export APP_ENV="${APP_ENV:-local}" LOG_STACK="${LOG_STACK:-single,stderr}"
if [ $CACHE = 1 ]; then php artisan config:cache >/dev/null && php artisan route:cache >/dev/null && echo "[$(date +%T)] bff: config and routes cached"; fi
"$FPM" -R -y "$D/php-fpm.conf" -c "$D/php.ini" &
FPM_PID=$!
trap 'php artisan config:clear >/dev/null 2>&1; php artisan route:clear >/dev/null 2>&1; kill $FPM_PID 2>/dev/null; kill $(cat "$D/run/nginx.pid" 2>/dev/null) 2>/dev/null; exit 0' INT TERM EXIT
for i in $(seq 30); do [ -S "$D/run/php-fpm.sock" ] && break; sleep 0.2; done
echo "[$(date +%T)] bff: nginx + php-fpm ($WORKERS workers as $RUNAS) on 127.0.0.1:$PORT"
"$NGINX" -c "$D/nginx.conf" -e /dev/stderr
