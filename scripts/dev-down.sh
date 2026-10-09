#!/usr/bin/env bash
# Stop everything scripts/dev-up.sh started. Leaves PostgreSQL, Redis, Kafka and MongoDB alone, and your data.
SESSION="${ENSURETECH_TMUX_SESSION:-ensuretech}"
tmux has-session -t "$SESSION" 2>/dev/null && { tmux kill-session -t "$SESSION"; echo "tmux session '$SESSION' closed"; }
sleep 1
for p in 8090 8087 8086 8085 8084 8083 8082 8081 8000 9090 9091; do
  pids="$(lsof -ti tcp:$p -sTCP:LISTEN 2>/dev/null)"; [ -n "$pids" ] && { kill $pids 2>/dev/null; echo "stopped the process on port $p"; }
done
pkill -f 'manage.py run_projector|relay_loop.py|manage.py run_consumer|manage.py run_sender' 2>/dev/null
exit 0
