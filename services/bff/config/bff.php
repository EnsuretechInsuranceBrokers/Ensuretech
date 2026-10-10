<?php

/*
 | The BFF is the public contract boundary (README, "BFF as the Public Contract Boundary"): it validates and sanitises what comes in, shapes what
 | goes out, composes views across services, and holds explicitly-owned, expiring mitigations. It holds NO business logic and NO database.
 */
return [
    // Shared with every core service (GATEWAY_SECRET there). Without it a service refuses the call, so nothing but this BFF can reach them.
    'gateway_secret' => env('BFF_GATEWAY_SECRET', ''),

    // Where each service lives on the private network. (Production: mTLS terminates in front of these; see README.)
    'upstreams' => [
        'identity' => env('UPSTREAM_IDENTITY', 'http://127.0.0.1:8000'),
        'organization' => env('UPSTREAM_ORGANIZATION', 'http://127.0.0.1:8081'),
        'policy' => env('UPSTREAM_POLICY', 'http://127.0.0.1:8082'),
        'endorsement' => env('UPSTREAM_ENDORSEMENT', 'http://127.0.0.1:8083'),
        'billing' => env('UPSTREAM_BILLING', 'http://127.0.0.1:8084'),
        'integration' => env('UPSTREAM_INTEGRATION', 'http://127.0.0.1:8085'),
        'read' => env('UPSTREAM_READ', 'http://127.0.0.1:8087'),
        'sheets' => env('UPSTREAM_SHEETS', 'http://127.0.0.1:8086'),
    ],

    // Tools that must never exist in production (they generate test data).
    'features' => ['qa_tools' => (bool) env('BFF_QA_TOOLS', false)],

    'timeouts' => [
        'connect' => (float) env('BFF_CONNECT_TIMEOUT', 2),
        'request' => (float) env('BFF_REQUEST_TIMEOUT', 10),
        'upload' => (float) env('BFF_UPLOAD_TIMEOUT', 120),
        'stream' => (float) env('BFF_STREAM_TIMEOUT', 300),
    ],

    // Tokens are issued by identity-service (RS256). The BFF verifies the signature, issuer, expiry and token type; the services behind it
    // verify again and make every authorisation decision (the BFF only refuses callers that clearly belong to another gateway).
    'jwt' => [
        'public_key_path' => env('BFF_JWT_PUBLIC_KEY_PATH'),
        'public_key' => env('BFF_JWT_PUBLIC_KEY'),
        'issuer' => env('BFF_JWT_ISSUER', 'ensuretech-identity'),
        'leeway' => (int) env('BFF_JWT_LEEWAY', 30),
    ],

    'gateways' => [
        'broker' => ['BROKER_ADMIN', 'BROKER_SPECIALIZER', 'BROKER_NON_ADMIN'],
        'employer' => ['EMPLOYER_ADMIN', 'EMPLOYER_NON_ADMIN'],
        'tpa' => ['TPA_USER'],
    ],

    // Production-operations staff (README s.10). identity-service does not issue these roles yet; until it does, /ops is closed to everyone.
    'ops_roles' => array_filter(explode(',', (string) env('BFF_OPS_ROLES', ''))),

    'rate_limits' => [
        'anonymous_per_minute' => (int) env('BFF_RATE_ANON', 120),     // per IP, before authentication: blunts token guessing
        'tenant_per_minute' => (int) env('BFF_RATE_TENANT', 600),      // per broker (or employer), shared by all its users
        'user_per_minute' => (int) env('BFF_RATE_USER', 240),          // per user
        'auth_per_minute' => (int) env('BFF_RATE_AUTH', 10),           // login / refresh, per IP
        'heavy_per_minute' => (int) env('BFF_RATE_HEAVY', 20),         // uploads and other expensive calls, per user
    ],

    // Only for wording on the "how many employees" page; the endorsement service's GRID_MAX_EMPLOYEES is what actually decides. Keep them equal.
    'grid_max_employees' => (int) env('BFF_GRID_MAX_EMPLOYEES', 100),

    'limits' => [
        'max_upload_bytes' => (int) env('BFF_MAX_UPLOAD_BYTES', 100 * 1024 * 1024),
        'max_json_body_bytes' => (int) env('BFF_MAX_JSON_BODY_BYTES', 256 * 1024),
        'max_download_bytes' => (int) env('BFF_MAX_DOWNLOAD_BYTES', 200 * 1024 * 1024),
    ],

    // Response mitigations (README s.9). A file of entries with owner, reason, expiry and removal condition; see App\Bff\Mitigations.
    'mitigations_path' => env('BFF_MITIGATIONS_PATH', storage_path('bff/mitigations.json')),
];
