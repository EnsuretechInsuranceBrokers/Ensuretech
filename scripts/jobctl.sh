#!/usr/bin/env bash
# Command-line controls for the TPA sync jobs (integration-service), for an operator who holds super credentials. Talks to the service directly with a SYSTEM token.
#
#   scripts/jobctl.sh list [STATUS]                 jobs, active first (STATUS: PENDING IN_PROGRESS DEAD CANCELLED SUCCEEDED SKIPPED)
#   scripts/jobctl.sh show JOB_ID
#   scripts/jobctl.sh audit                         who tried to do what, and whether the password was right
#   scripts/jobctl.sh kill JOB_ID "reason"          stop a pending or running job            (asks for the job-killer password)
#   scripts/jobctl.sh requeue JOB_ID                put a dead or cancelled job back         (asks for the job-killer password)
#   scripts/jobctl.sh set-password                  change the job-killer password           (asks for the current and the new one)
#
# The password is read from a prompt (not an argument, so it never lands in your shell history or the process list) unless JOB_KILLER_PASSWORD is set.
# Needs local.env (SERVICE_CLIENT_ID/SECRET come from ENGINE_CLIENT_ID/SECRET) and python3 for formatting.
set -u
cd "$(dirname "${BASH_SOURCE[0]}")/.."; set -a; . ./local.env; set +a
BASE="${INTEGRATION_URL:-http://127.0.0.1:8085}/api/v1/ops/jobs"
TOKEN="$(curl -s "$IDENTITY_TOKEN_URL" -d "grant_type=client_credentials&client_id=$ENGINE_CLIENT_ID&client_secret=$ENGINE_CLIENT_SECRET" | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])' 2>/dev/null)"
[ -n "$TOKEN" ] || { echo "could not get a SYSTEM token from $IDENTITY_TOKEN_URL (is identity running? are the service-account values in local.env right?)"; exit 1; }
ask(){ [ -n "${JOB_KILLER_PASSWORD:-}" ] && { PW="$JOB_KILLER_PASSWORD"; return; }; read -rsp "${1:-Job-killer password}: " PW; echo; }
call(){ curl -s -w '\n%{http_code}' -H "Authorization: Bearer $TOKEN" "$@"; }
show(){ python3 -I "$(dirname "${BASH_SOURCE[0]}")/jobctl_fmt.py"; }
case "${1:-}" in
  list)   call "$BASE${2:+?status=$2}" | show;;
  show)   call "$BASE/${2:?job id}" | show;;
  audit)  call "$BASE/audit" | show;;
  kill)   ask; call -X POST "$BASE/${2:?job id}/kill" -H "X-Job-Killer-Password: $PW" -H 'Content-Type: application/json' -d "$(python3 -c 'import json,sys;print(json.dumps({"reason":sys.argv[1]}))' "${3:?say why: scripts/jobctl.sh kill ID \"reason\"}")" | show;;
  requeue) ask; call -X POST "$BASE/${2:?job id}/requeue" -H "X-Job-Killer-Password: $PW" | show;;
  set-password) ask "Current job-killer password"; read -rsp "New password (12+ characters): " NEW; echo; call -X PUT "$BASE/password" -H "X-Job-Killer-Password: $PW" -H 'Content-Type: application/json' -d "$(python3 -c 'import json,sys;print(json.dumps({"newPassword":sys.argv[1]}))' "$NEW")" | show;;
  *) sed -n '2,13p' "$0"; exit 2;;
esac
