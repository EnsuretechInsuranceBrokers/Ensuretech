#!/usr/bin/env bash
# One-command sanity run against a running stack (scripts/dev-up.sh), driving it only through the doors a user or operator has. Prints PASS/FAIL per check, exits non-zero if any fail.
# It automates run.html sections 10 to 12: sign-in, the main flow through Celery, the TPA offline upload, the lock on the core services, and the job controls.
#
#   qa/smoke.sh              everything
#   qa/smoke.sh --quick      sign-in, lock and controls only (no endorsement upload; about 10 seconds)
#
# Safe to repeat: each run uses fresh employee codes and PANs; the TPA upload is idempotent; it kills only a job it creates itself. Needs: curl, python3, a stack started with
# scripts/dev-up.sh on the seeded database (\i setup.sql). With --no-kafka the dashboards and cross-service events are not checked (they cannot work), and that is reported, not failed.
set -u
cd "$(dirname "${BASH_SOURCE[0]}")/.."; set -a; . ./local.env; set +a
QUICK=0; [ "${1:-}" = --quick ] && QUICK=1
B=http://127.0.0.1:8090/api/v1; PASS=0; FAIL=0; SKIP=0
ok(){ PASS=$((PASS+1)); printf '  \033[32mPASS\033[0m %s\n' "$*"; }
no(){ FAIL=$((FAIL+1)); printf '  \033[31mFAIL\033[0m %s\n' "$*"; }
skip(){ SKIP=$((SKIP+1)); printf '  \033[33mSKIP\033[0m %s\n' "$*"; }
code(){ curl -s -o /dev/null -m 15 -w '%{http_code}' "$@"; }
expect(){ local want="$1" label="$2"; shift 2; local got; got="$(code "$@")"; [ "$got" = "$want" ] && ok "$label ($got)" || no "$label: expected $want, got $got"; }
jget(){ python3 -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }
# Sign-in is rate limited (that is the lock working), and tokens last 10 minutes: reuse one for 8, and wait out a 429 instead of failing on it.
login(){ local f=".dev/smoke-$1.token"; mkdir -p .dev
  [ -f "$f" ] && [ -n "$(find "$f" -mmin -8 2>/dev/null)" ] && { cat "$f"; return; }
  local t i; for i in 1 2 3 4; do
    t="$(curl -s -m 15 "$B/auth/login" -H 'Content-Type: application/json' -d "{\"email\":\"$1\",\"password\":\"Str0ng-Pass-123!\"}" | jget 'd["access_token"]')"
    [ -n "$t" ] && { printf '%s' "$t" > "$f"; printf '%s' "$t"; return; }
    sleep 20; done; }
up(){ (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

echo "0. The stack"
for pair in identity:8000 organization:8081 policy:8082 engine:8083 billing:8084 integration:8085 bff:8090; do up "${pair#*:}" && ok "${pair%%:*} is listening on ${pair#*:}" || { no "${pair%%:*} is not listening on ${pair#*:}: start it with scripts/dev-up.sh"; }; done
[ $FAIL -gt 0 ] && { echo; echo "Stack is not up; stopping."; exit 1; }

echo "1. Sign-in"
BROKER="$(login meera.kapoor@northwind.example)"; [ -n "$BROKER" ] && ok "broker admin signs in through the BFF" || { no "broker sign-in"; exit 1; }
HR="$(login priya.menon@acme.example)"; [ -n "$HR" ] && ok "employer HR signs in" || no "HR sign-in"
READONLY="$(login nisha.rao@northwind.example)"; OTHER="$(login admin@southbridge.example)"
expect 401 "wrong password is refused" "$B/auth/login" -H 'Content-Type: application/json' -d '{"email":"meera.kapoor@northwind.example","password":"nope-nope-nope"}'
expect 200 "broker sees policies" -H "Authorization: Bearer $BROKER" "$B/broker/policies"
expect 200 "HR sees policies" -H "Authorization: Bearer $HR" "$B/employer/policies"
expect 403 "HR cannot use the broker door" -H "Authorization: Bearer $HR" "$B/broker/policies"
n_own="$(curl -s -H "Authorization: Bearer $BROKER" "$B/broker/policies" | jget 'len(d.get("items",d))')"; n_other="$(curl -s -H "Authorization: Bearer $OTHER" "$B/broker/policies" | jget 'len(d.get("items",d))')"
[ "${n_other:-0}" = 0 ] && [ "${n_own:-0}" -gt 0 ] && ok "another broker sees none of Northwind's $n_own policies" || no "tenant isolation: own=$n_own other=$n_other"

echo "2. The lock: nobody reaches a core service directly"
for t in "8081 /api/v1/broker/employers" "8082 /api/v1/broker/policies" "8084 /api/v1/broker/cd/accounts" "8085 /api/v1/broker/integration/tpa-pending" "8083 /api/v1/broker/endorsements/" "8083 /api/v1/internal/members/pending-summary/" "8000 /api/v1/auth/me/" "8000 /api/v1/users/"; do
  set -- $t; expect 403 "direct to :$1$2, valid broker token" -H "Authorization: Bearer $BROKER" "127.0.0.1:$1$2"; done
expect 403 "direct login to identity" -H 'Content-Type: application/json' -d '{"email":"meera.kapoor@northwind.example","password":"Str0ng-Pass-123!"}' 127.0.0.1:8000/api/v1/auth/login/
for p in loggers threaddump scheduledtasks; do expect 403 "actuator/$p with a broker token" -H "Authorization: Bearer $BROKER" 127.0.0.1:8085/actuator/$p; done
expect 200 "health stays open for load balancers" 127.0.0.1:8085/actuator/health
expect 403 "metrics without the metrics token" 127.0.0.1:8085/actuator/prometheus
expect 200 "metrics with the metrics token" -H "Authorization: Bearer $METRICS_TOKEN" 127.0.0.1:8085/actuator/prometheus
SYS="$(curl -s -m 15 127.0.0.1:8000/api/v1/auth/service-token/ -d "grant_type=client_credentials&client_id=$ENGINE_CLIENT_ID&client_secret=$ENGINE_CLIENT_SECRET" | jget 'd["access_token"]')"
[ -n "$SYS" ] && ok "super credentials sign in for a SYSTEM token" || no "service-token sign-in"
expect 401 "wrong super-credential secret" 127.0.0.1:8000/api/v1/auth/service-token/ -d "grant_type=client_credentials&client_id=$ENGINE_CLIENT_ID&client_secret=wrong"
expect 200 "SYSTEM token reaches an internal endpoint directly" -H "Authorization: Bearer $SYS" "127.0.0.1:8083/api/v1/internal/members/pending-summary/?policy_ids=1"
expect 403 "SYSTEM token has no tenant, so no broker screen" -H "Authorization: Bearer $SYS" 127.0.0.1:8085/api/v1/broker/integration/tpa-pending

echo "3. Job controls"
expect 200 "SYSTEM lists sync jobs" -H "Authorization: Bearer $SYS" 127.0.0.1:8085/api/v1/ops/jobs
expect 403 "a broker cannot list them" -H "Authorization: Bearer $BROKER" 127.0.0.1:8085/api/v1/ops/jobs
GHOST=99999999-9999-4999-8999-999999999999
expect 403 "kill with a wrong job-killer password" -X POST -H "Authorization: Bearer $SYS" -H 'X-Job-Killer-Password: definitely-wrong' -H 'Content-Type: application/json' -d '{"reason":"smoke"}' 127.0.0.1:8085/api/v1/ops/jobs/$GHOST/kill
expect 404 "kill with the right password finds no such job" -X POST -H "Authorization: Bearer $SYS" -H "X-Job-Killer-Password: $JOB_KILLER_BOOTSTRAP_PASSWORD" -H 'Content-Type: application/json' -d '{"reason":"smoke"}' 127.0.0.1:8085/api/v1/ops/jobs/$GHOST/kill
A="$(curl -s -H "Authorization: Bearer $SYS" 127.0.0.1:8085/api/v1/ops/jobs/audit | jget '[x["outcome"] for x in d[:2]]')"; echo "$A" | grep -q WRONG_PASSWORD && ok "both attempts are in the audit trail $A" || no "audit trail: $A"

echo "3b. Break glass and the live query log"
BGID="$(psql "$IDENTITY_DB" -Atc "select id from identity_users where email='meera.kapoor@northwind.example'" 2>/dev/null)"
if [ -n "${BREAK_GLASS_PASSWORD:-}" ] && [ -n "$BGID" ]; then
  bg(){ curl -s -m 15 -w '\n%{http_code}' 127.0.0.1:8000/api/v1/auth/break-glass/ -H "Authorization: Bearer ${2-$SYS}" -H 'Content-Type: application/json' -d "{\"break_glass_password\":\"$1\",\"user_id\":\"$BGID\",\"reason\":\"smoke test: break-glass drill\"}"; }
  [ "$(bg wrong-wrong-wrong-wrong | tail -1)" = 403 ] && ok "break glass: wrong password refused (403)" || no "break glass accepted a wrong password"
  [ "$(bg "$BREAK_GLASS_PASSWORD" "$BROKER" | tail -1)" = 403 ] && ok "break glass: a broker's own token cannot ask (403)" || no "break glass answered a non-SYSTEM caller"
  BGT="$(bg "$BREAK_GLASS_PASSWORD" | head -1 | jget 'd["access_token"]')"
  [ -n "$BGT" ] && ok "break glass: SYSTEM + password gets a 15-minute token for one named user" || no "break glass grant"
  expect 200 "break glass token works straight against policy-service, no BFF" -H "Authorization: Bearer $BGT" 127.0.0.1:8082/api/v1/broker/policies
  expect 200 "...and against organization-service" -H "Authorization: Bearer $BGT" "127.0.0.1:8081/api/v1/broker/employers?size=1"
  expect 403 "break glass token is not a SYSTEM token (no actuator controls)" -H "Authorization: Bearer $BGT" 127.0.0.1:8082/actuator/loggers
else skip "break glass: BREAK_GLASS_PASSWORD or psql not available"; fi
if [ "${QUERYLOG_ENABLED:-false}" = true ] && up 8100 && ! curl -s -m 20 -u "$ADMIN_UI_USER:$ADMIN_UI_PASSWORD" "127.0.0.1:8100/ops-api/queries?limit=1" | grep -q "query log is off"; then
  sleep 2; Q="$(curl -s -m 20 -u "$ADMIN_UI_USER:$ADMIN_UI_PASSWORD" "127.0.0.1:8100/ops-api/queries?limit=50")"
  echo "$Q" | python3 -c "import sys,json;d=json.load(sys.stdin);bad=[k for k,v in d['services'].items() if v!='ok'];rows=[r for r in d['requests'] if r['kind']=='request' and r.get('role')];sys.exit(0 if not bad and rows else 1)" 2>/dev/null \
    && ok "live query log: every service reports in, and requests carry user, role and controller method" || no "query log: $(echo "$Q" | head -c 300)"
else skip "live query log is off (QUERYLOG_ENABLED) or the console is not running"; fi

echo "4. TPA offline upload (member ids by spreadsheet)"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
expect 200 "board of members waiting" -H "Authorization: Bearer $BROKER" "$B/broker/integration/tpa-pending"
expect 200 "template downloads" -o "$T/t.xlsx" -H "Authorization: Bearer $BROKER" "$B/broker/integration/policies/1/offline-template"
printf 'Employee code,Relationship,TPA member id,TPA member name,Cover start date\n1001,Self,T-1001,Asha Rao,2026-11-01\n1099,Self,T-1099,Nobody,2026-11-01\n1004,Self,T-1006,Dev Iyer,2026-12-15\n' > "$T/tpa.csv"
R="$(curl -s -m 30 -H "Authorization: Bearer $BROKER" -F file=@"$T/tpa.csv" -F mode=preview "$B/broker/integration/policies/1/offline-sheet")"
[ "$(echo "$R" | jget '(d["applied"],d["rejected"])')" = "(False, 2)" ] && ok "preview: the person on the policy passes, the stranger and the wrong cover date are refused, nothing saved" || no "preview result: $R"
echo "$R" | jget '[l["reason_code"] for l in d["lines"] if l["reason_code"]]' | grep -q EMPLOYEE_NOT_ON_POLICY && ok "refusals carry a reason code and line number" || no "reason codes"
[ "$(curl -s -H "Authorization: Bearer $READONLY" -F file=@"$T/tpa.csv" -F mode=apply "$B/broker/integration/policies/1/offline-sheet" -o /dev/null -w '%{http_code}')" = 403 ] && ok "the read-only broker cannot upload" || no "read-only broker upload"
expect 404 "another broker cannot see the policy" -H "Authorization: Bearer $OTHER" "$B/broker/integration/policies/1/tpa-pending"
TPAU="$(login tpa@example.test)"
[ -z "$TPAU" ] && { curl -s -o /dev/null -X POST "$B/broker/users" -H "Authorization: Bearer $BROKER" -H 'Content-Type: application/json' -d '{"email":"tpa@example.test","first_name":"Tara","last_name":"Tpa","role":"TPA_USER","tpa_id":3,"password":"Str0ng-Pass-123!"}'; TPAU="$(login tpa@example.test)"; }
[ -n "$TPAU" ] && expect 200 "the TPA's own user sees the board" -H "Authorization: Bearer $TPAU" "$B/tpa/integration/tpa-pending" || no "TPA user"
expect 403 "the TPA user cannot use the broker door" -H "Authorization: Bearer $TPAU" "$B/broker/policies"

if [ $QUICK = 0 ]; then
echo "5. An endorsement through the real Celery pipeline"
SUF=$(( RANDOM % 9000 + 1000 )); L=$(python3 -c "import random,string;print(''.join(random.choices(string.ascii_uppercase,k=5)))"); L2=$(python3 -c "import random,string;print(''.join(random.choices(string.ascii_uppercase,k=5)))")
printf 'Emp Code,Relationship,Member Name,DOB,Gender,Sum Insured,Effective Date,PAN\n%s,Self,Smoke Tester,17/05/1985,F,500000,,%s%sQ\n%s,Spouse,Smoke Spouse,02-Feb-1983,M,500000,,\n%s,SON,Smoke Kid,01/01/2015,M,500000,,\n' "$SUF" "$L" "$SUF" "$SUF" "$SUF" > "$T/e.csv"
JOB="$(curl -s -m 30 -X POST "$B/broker/endorsements/csv/upload" -H "Authorization: Bearer $BROKER" -H "Idempotency-Key: smoke-$$-$SUF" -H "X-Correlation-Id: smoke-trace-$$-$SUF" -F file=@"$T/e.csv" -F policy_id=1 -F op_type=ADD -F effective_date=2026-11-15 | jget 'd["id"]')"
[ -n "$JOB" ] && ok "upload accepted: job ${JOB:0:8}" || no "upload"
S=""; for i in $(seq 40); do S="$(curl -s -H "Authorization: Bearer $BROKER" "$B/broker/endorsements/$JOB" | jget 'd["status"]')"; case "$S" in ENDORSED|*FAILED*|CD_INSUFFICIENT|VALIDATION_PARTIAL) break;; esac; sleep 1; done
[ "$S" = ENDORSED ] && ok "the job reached ENDORSED" || no "job ended at '$S' (CD_INSUFFICIENT: credit the deposit; VALIDATION_PARTIAL: see /endorsements/$JOB/errors)"
if [ -d .dev/logs ]; then
  sleep 1; TR="smoke-trace-$$-$SUF"; SEEN="$(grep -l -- "$TR" .dev/logs/*.log 2>/dev/null | xargs -n1 basename 2>/dev/null | sed 's/\.log$//' | tr '\n' ' ')"
  case "$SEEN" in *bff*engine*|*bff*endorsement*) ok "one correlation id appears in the logs of: ${SEEN}";; *) [ -n "$SEEN" ] && no "correlation id only reached: $SEEN" || skip "correlation id not found in .dev/logs (stack not started by dev-up.sh?)";; esac
fi
if up 5555; then
  W="$(curl -s -u "$FLOWER_BASIC_AUTH" "127.0.0.1:5555/api/tasks?limit=200" | python3 -c "
import sys,json
d=json.load(sys.stdin); job='$JOB'
names={t['name'].split('.')[-1]+'@'+t['worker'].split('@')[0] for t in d.values() if job in str(t.get('args'))}
print(' '.join(sorted(names)))")"
  echo "$W" | grep -q "ingest_csv@ingest" && echo "$W" | grep -q "validate_chunk@validation" && echo "$W" | grep -q "apply_chunk@computation" && ok "Flower saw each stage on its own worker: $W" || no "Flower tasks for the job: '$W'"
else skip "Flower is not running"; fi
fi

echo; printf 'Result: %s passed, %s failed, %s skipped\n' $PASS $FAIL $SKIP
[ $FAIL = 0 ]
