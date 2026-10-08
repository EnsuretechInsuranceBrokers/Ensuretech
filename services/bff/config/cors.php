<?php

// Browsers may call the API only from the origins listed in BFF_CORS_ORIGINS (comma separated). Default: none, i.e. no cross-origin access.
return [
    'paths' => ['api/*'],
    'allowed_methods' => ['GET', 'POST', 'OPTIONS'],
    'allowed_origins' => array_values(array_filter(array_map('trim', explode(',', (string) env('BFF_CORS_ORIGINS', ''))))),
    'allowed_origins_patterns' => [],
    'allowed_headers' => ['Authorization', 'Content-Type', 'Idempotency-Key', 'Accept', 'Accept-Language', 'X-Correlation-Id'],
    'exposed_headers' => ['X-Correlation-Id', 'X-RateLimit-Limit', 'Retry-After', 'X-Mitigations'],
    'max_age' => 600,
    'supports_credentials' => false,
];
