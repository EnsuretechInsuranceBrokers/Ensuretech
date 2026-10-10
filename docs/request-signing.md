# How a request is signed, authenticated and authorised

Two different proofs travel with every call from the portal to a core service. They answer two different questions, and a core service needs both.

| Question | Proof | Who checks it |
|---|---|---|
| Did this call really come through the BFF? | **A signature** made with a secret only the BFF and the core services share | each core service's gateway guard (`GatewaySecretFilter` in Spring, `config/gateway.py` in Django) |
| Who is the person, and what may they touch? | **The person's access token** (a JWT signed by identity-service) | the BFF first, then again by the core service |

## 1. The browser to the BFF
The person signs in; the BFF calls identity-service and keeps the access and refresh tokens in its own server-side session. The browser holds only a session cookie (and the portal pages carry a CSRF token). The BFF verifies every token itself (`JwtVerifier`: RS256 signature against identity's public key, issuer, expiry, `token_type=access`), applies its rate limits, and refuses any path or field that is not in the contract (`config/contract.php`, `contract/*.php`). Only then does it call a core service.

## 2. The BFF to a core service: the signature
For each upstream call the BFF adds three headers (`GatewaySeal::middleware`, applied to every outgoing request):

```
X-Gateway-Timestamp  unix seconds
X-Gateway-Nonce      24 random hex characters, never reused
X-Gateway-Signature  HMAC-SHA256(secret, "v1\n" ts "\n" nonce "\n" METHOD "\n" path "\n" query "\n" sha256(Authorization header))
```
The secret (`GATEWAY_SECRET` on the core services, `BFF_GATEWAY_SECRET` on the BFF, 32+ characters) never travels. Because the signed text includes the method, the exact path, the query string and a hash of the person's token, a signature is good for **one** call: it cannot be used on another URL, with another verb, or with another person's token.

## 3. What a core service does with a request, in order
1. **Correlation id**: read or replaced, put on every log line.
2. **Gateway guard** (before anything is parsed or any database is touched): recompute the HMAC and compare in constant time; the timestamp must be within 60 seconds; the nonce must not have been seen (remembered only after the signature checks out, so strangers cannot fill the cache; a full cache refuses rather than forgets). Anything else gets an empty 403. The only other ways through are a verified **SYSTEM** token (a service account's super credentials), a **break-glass** token (docs/break-glass.md), the metrics token on `/actuator/prometheus` only, and the open doors: health probes and identity's token sign-in.
3. **Authentication**: Spring's resource server (or the Django authentication class) verifies the person's JWT again with identity's **public** key: RS256 signature, issuer, expiry, and that it is an access token, not a refresh token. A core service cannot mint tokens, only check them.
4. **Tenant context**: `broker_id`, `employer_id`, `tpa_id` and `role` are copied from the verified token (never from the request body or URL) into a per-request context that is cleared afterwards.
5. **Authorisation by role**: the route rules (`SecurityConfig`) decide which roles may use which URLs (`/api/v1/broker/**` for the three broker roles, `/api/v1/employer/**` for employer roles, `/actuator/**` and `/api/v1/ops/**` for SYSTEM only), anything not listed is denied, and method-level rules narrow it further (for example only a broker admin may record a deposit).
6. **Authorisation by tenant**: every query includes the broker (and employer) from the token, so another firm's rows are never selected, and an id that belongs to someone else answers 404, not 403. The BFF does not decide this; the core service does, so a mistake in the BFF cannot leak data.

## 4. What this protects against, and what it does not
- Protects: reaching a core service without going through the BFF (curl, Postman, a port scan: 403 before any work), replaying or editing a captured call, using one person's token on another person's URL, a stolen browser session being used against a core service directly.
- Does not protect: eavesdropping. The signature proves origin and integrity; it does not encrypt. Between the BFF and a core service the traffic is plain HTTP unless you put TLS on it (loopback or a private network, or mutual TLS). Terminate HTTPS for the browser at nginx. Anyone who holds the shared secret or a SYSTEM service account's credentials can act as the BFF, so keep them in a secret manager and rotate them.
