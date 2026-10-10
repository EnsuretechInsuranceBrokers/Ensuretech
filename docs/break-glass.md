# Break glass: working without the BFF

The core services (organization, policy, billing, integration, sheet-generator, identity, the endorsement engine) refuse every request that did not come through the BFF, except a verified **SYSTEM** token (docs/core-lockdown.md). A SYSTEM token has no tenant, so it cannot use the broker and employer screens. If the BFF is down and someone has to finish a broker's work, that is the gap break glass fills.

## What it is
A way for an operator who holds **both** the super credentials **and** the break-glass password to get a short-lived token that acts as **one named, existing, active user**: that user's real role, broker and employer, so every tenant rule still applies. The core services accept that token without a BFF signature.

| | |
|---|---|
| Who can ask | A verified SYSTEM token (a service account) **and** `BREAK_GLASS_PASSWORD` |
| What they get | An access token for one user, valid 15 minutes (`BREAK_GLASS_MINUTES`, never more than 60), no refresh token |
| What it can do | Whatever that user could do through the BFF. Not SYSTEM powers (no `/actuator` controls, no gRPC, no job-killer) |
| What is recorded | Every attempt, granted or refused, in `identity_break_glass_grants` (who, which user, why, expiry, source address) and in identity's log at WARNING. The token carries the operator and the reason, and the live request dashboard marks every request made with one **BREAK-GLASS** |
| Switched off when | `BREAK_GLASS_PASSWORD` is shorter than 16 characters or unset: the endpoint answers 404. Wrong guesses are rate limited (`BREAK_GLASS_THROTTLE_RATE`, default 5 a minute) |

A reason of at least 10 characters is required. Nothing here weakens the normal lock: a user token without the `break_glass` claim, which only identity can mint for the two together, is still refused.

## Using it
```bash
scripts/breakglass.sh users meera                       # find the user id to act as (local database)
scripts/breakglass.sh call USER_ID "BFF down, finishing Acme's endorsement" GET policy /api/v1/broker/policies
scripts/breakglass.sh call USER_ID "why" POST billing /api/v1/broker/cd/accounts/1/credits '{"amount":100000,"reference":"R-1","type":"DEPOSIT","narration":"top-up"}'
scripts/breakglass.sh token USER_ID "why"               # just the token, to use with curl or the load tester
scripts/breakglass.sh audit                             # who asked, for whom, and what happened
```
Services: `identity org policy engine billing integration sheet read`. The script reads `BREAK_GLASS_PASSWORD` from `local.env` (or prompts) and the super credentials from `ENGINE_CLIENT_ID/SECRET`.

By hand: sign in with the service account for a SYSTEM token, then
`POST /api/v1/auth/break-glass/` with `Authorization: Bearer <system token>` and `{"break_glass_password":"…","user_id":"<uuid>","reason":"…"}`; use the returned `access_token` as a bearer token directly against the service's port.

## Where the password lives
Not in git. `local.env` ends by sourcing `local.env.private` if it exists (the file is in `.gitignore`). Create it at the repo root on each machine:
```bash
export BREAK_GLASS_PASSWORD='choose-16-or-more-characters'
export BREAK_GLASS_THROTTLE_RATE=30/min      # optional, for repeated QA runs; the default is 5 a minute
```
Without the file the feature is off (the endpoint answers 404) and `scripts/breakglass.sh` prompts for a password.

## Before this goes anywhere real
- `local.env` holds a **development** password. Replace it with a long random one from your secret manager (`openssl rand -base64 24`) and rotate it after every use.
- Keep the service ports (8081 to 8087, 8000) off public networks; the lock does not make them safe to expose.
- Review `identity_break_glass_grants` regularly. A grant nobody can explain is an incident.
- Decide who may hold the super credentials; break glass needs them as well as the password.

Tests: `identity-service/tests/test_break_glass.py` (grant, refusals, cap, the guard), the gateway tests in policy-service and the engine, and `qa/smoke.sh` section 3b.
