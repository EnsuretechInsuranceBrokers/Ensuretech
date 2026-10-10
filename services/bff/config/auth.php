<?php

// The BFF has no users or sessions of its own: identity-service issues the tokens and App\Bff\JwtVerifier checks them.
return [
    'defaults' => ['guard' => 'web', 'passwords' => 'users'],
    'guards' => [],
    'providers' => [],
    'passwords' => [],
    'password_timeout' => 10800,
];
