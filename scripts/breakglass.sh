#!/usr/bin/env bash
# Break glass: act as ONE named user straight against a core service, bypassing the BFF (for when the BFF is down). Every grant is audited. See docs/break-glass.md.
#
#   scripts/breakglass.sh users [EMAIL-FRAGMENT]                       find the user id to act as (local database only)
#   scripts/breakglass.sh token USER_ID "reason"                       print a 15-minute token for that user
#   scripts/breakglass.sh call USER_ID "reason" METHOD SERVICE PATH [JSON-BODY]
#                                                                      e.g. call 6c1f... "BFF down, add a policy" GET policy /api/v1/broker/policies
#   scripts/breakglass.sh audit                                        every attempt, granted or refused (local database only)
#
# SERVICE: identity org policy engine billing integration sheet read.  The password comes from BREAK_GLASS_PASSWORD (local.env) or a prompt.
# Needs local.env (ENGINE_CLIENT_ID/SECRET are the super credentials), curl and python3.
set -u
cd "$(dirname "${BASH_SOURCE[0]}")/.."; set -a; . ./local.env; set +a
port(){ case "$1" in identity) echo 8000;; org) echo 8081;; policy) echo 8082;; engine) echo 8083;; billing) echo 8084;; integration) echo 8085;; sheet) echo 8086;; read) echo 8087;; *) echo "unknown service '$1'" >&2; return 1;; esac; }
sys_token(){ curl -s "$IDENTITY_TOKEN_URL" -d "grant_type=client_credentials&client_id=$ENGINE_CLIENT_ID&client_secret=$ENGINE_CLIENT_SECRET" | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])' 2>/dev/null; }
grant(){   # $1 user id, $2 reason  ->  prints the access token, or the refusal on stderr
  local pw="${BREAK_GLASS_PASSWORD:-}" st out; [ -n "$pw" ] || { read -rsp "Break-glass password: " pw; echo >&2; }
  st="$(sys_token)"; [ -n "$st" ] || { echo "could not get a SYSTEM token (is identity running?)" >&2; return 1; }
  out="$(python3 -c 'import json,sys;print(json.dumps({"break_glass_password":sys.argv[1],"user_id":sys.argv[2],"reason":sys.argv[3]}))' "$pw" "$1" "$2" | curl -s -w '\n%{http_code}' "${IDENTITY_TOKEN_URL%/service-token/}/break-glass/" -H "Authorization: Bearer $st" -H 'Content-Type: application/json' -d @-)"
  [ "${out##*$'\n'}" = 200 ] || { echo "refused (${out##*$'\n'}): ${out%$'\n'*}" >&2; return 1; }
  printf '%s' "${out%$'\n'*}" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d["access_token"]);print("acting as",d["acting_as"],"for",d["expires_in"]//60,"minutes; grant",d["grant_id"],file=sys.stderr)'
}
case "${1:-}" in
  users) psql "${IDENTITY_DB}" -c "select id, email, role, broker_id, employer_id from identity_users where is_active and email ilike '%${2:-}%' order by broker_id, role limit 50";;
  audit) psql "${IDENTITY_DB}" -c "select attempted_at, outcome, client_id, target_user_id, reason from identity_break_glass_grants order by attempted_at desc limit 50";;
  token) grant "${2:?user id}" "${3:?say why}";;
  call)  T="$(grant "${2:?user id}" "${3:?say why}")" || exit 1; P="$(port "${5:?service}")" || exit 1
         curl -s -w '\n%{http_code}\n' -X "${4:?method}" "http://127.0.0.1:$P${6:?path}" -H "Authorization: Bearer $T" ${7:+-H 'Content-Type: application/json' -d "$7"};;
  *) sed -n '2,12p' "$0"; exit 2;;
esac
