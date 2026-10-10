# identity-service

Django 6.1 / DRF / SimpleJWT. Issues RS256 access tokens; every other service verifies them with the public key only.

## Users
`/api/v1/auth/{login,refresh,logout,me,change-password}/`, tenant-scoped `POST /api/v1/users/`. Tokens carry `role`, `broker_id`,
`employer_id`, `tpa_id`.

## Machine callers: client-credentials
`POST /api/v1/auth/service-token/` with `grant_type=client_credentials` and `client_id` / `client_secret` (form, JSON or HTTP Basic).
Returns `{access_token, token_type: "Bearer", expires_in}` (default 10 min, `SERVICE_TOKEN_MINUTES`); no refresh token.
The token is a normal access token with `role=SYSTEM`, `user_id=sub=svc:<client_id>`, `service=<name>`, no tenant claims, so resource
servers accept it unchanged. It does not authenticate on identity's own user APIs (401).

Manage identities with `python manage.py service_account create|rotate|revoke|list`:
- secrets are random 384-bit, shown once, stored only as SHA-256; compared in constant time;
- `rotate --grace-minutes N` issues a new secret and lets the old one live N more minutes (zero-downtime rollout);
- `revoke` stops new tokens at once; tokens already issued live until expiry (<= 10 min);
- failures are always `401 invalid_client` (no hint whether the client exists); throttled by `SERVICE_AUTH_THROTTLE_RATE` (120/min/IP).

Not done: per-service scopes/audiences (every service account is SYSTEM everywhere), an admin API for accounts (CLI only), mTLS.
