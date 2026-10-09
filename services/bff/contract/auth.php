<?php

// Sign-in and the signed-in user. Login and refresh are the only endpoints that work without a token.
$userFields = ['id' => 'id', 'email' => 'email', 'first_name' => 'first_name', 'last_name' => 'last_name', 'phone' => 'phone', 'role' => 'role',
    'broker_id' => 'broker_id', 'employer_id' => 'employer_id', 'tpa_id' => 'tpa_id', 'is_active' => 'is_active'];

return [
    'schemas' => [
        'tokens' => ['access' => 'access_token', 'refresh' => 'refresh_token'],
        'user' => $userFields,
        'created_user' => ['email' => 'email', 'first_name' => 'first_name', 'last_name' => 'last_name', 'phone' => 'phone', 'role' => 'role',
            'broker_id' => 'broker_id', 'employer_id' => 'employer_id', 'tpa_id' => 'tpa_id'],
    ],
    'inputs' => [
        'login' => ['email' => ['type' => 'string', 'required' => true, 'max' => 254, 'pattern' => '/^[^@\s]+@[^@\s]+$/'],
            'password' => ['type' => 'string', 'required' => true, 'max' => 200, 'min' => 1]],
        'token_refresh' => ['refresh_token' => ['to' => 'refresh', 'type' => 'string', 'required' => true, 'max' => 4096]],
        'change_password' => ['old_password' => ['type' => 'string', 'required' => true, 'max' => 200], 'new_password' => ['type' => 'string', 'required' => true, 'max' => 200, 'min' => 8]],
        'user_create_broker' => [
            'email' => ['type' => 'string', 'required' => true, 'max' => 254, 'pattern' => '/^[^@\s]+@[^@\s]+$/'],
            'first_name' => ['type' => 'string', 'required' => true, 'max' => 100], 'last_name' => ['type' => 'string', 'required' => true, 'max' => 100], 'phone' => ['type' => 'string', 'max' => 20],
            'role' => ['type' => 'string', 'required' => true, 'enum' => ['BROKER_ADMIN', 'BROKER_SPECIALIZER', 'BROKER_NON_ADMIN', 'EMPLOYER_ADMIN', 'EMPLOYER_NON_ADMIN', 'TPA_USER']],
            'password' => ['type' => 'string', 'required' => true, 'max' => 200, 'min' => 8], 'employer_id' => ['type' => 'int', 'min' => 1], 'tpa_id' => ['type' => 'int', 'min' => 1]],
        'user_create_employer' => [
            'email' => ['type' => 'string', 'required' => true, 'max' => 254, 'pattern' => '/^[^@\s]+@[^@\s]+$/'],
            'first_name' => ['type' => 'string', 'required' => true, 'max' => 100], 'last_name' => ['type' => 'string', 'required' => true, 'max' => 100], 'phone' => ['type' => 'string', 'max' => 20],
            'role' => ['type' => 'string', 'required' => true, 'enum' => ['EMPLOYER_ADMIN', 'EMPLOYER_NON_ADMIN']],
            'password' => ['type' => 'string', 'required' => true, 'max' => 200, 'min' => 8]],
    ],
    'endpoints' => [
        'auth.login' => ['scope' => 'public', 'methods' => ['POST'], 'path' => 'auth/login', 'gateways' => [], 'service' => 'identity', 'upstream' => '/api/v1/auth/login/',
            'input' => 'login', 'response' => 'tokens'],
        'auth.refresh' => ['scope' => 'public', 'methods' => ['POST'], 'path' => 'auth/refresh', 'gateways' => [], 'service' => 'identity', 'upstream' => '/api/v1/auth/refresh/',
            'input' => 'token_refresh', 'response' => 'tokens'],
        'auth.logout' => ['scope' => 'auth', 'methods' => ['POST'], 'path' => 'auth/logout', 'gateways' => [], 'service' => 'identity', 'upstream' => '/api/v1/auth/logout/',
            'input' => 'token_refresh', 'response' => 'none'],
        'auth.me' => ['scope' => 'auth', 'methods' => ['GET'], 'path' => 'auth/me', 'gateways' => [], 'service' => 'identity', 'upstream' => '/api/v1/auth/me/', 'response' => 'user'],
        'auth.change_password' => ['scope' => 'auth', 'methods' => ['POST'], 'path' => 'auth/change-password', 'gateways' => [], 'service' => 'identity',
            'upstream' => '/api/v1/auth/change-password/', 'input' => 'change_password', 'response' => 'none'],
        'users.create_broker' => ['methods' => ['POST'], 'path' => 'users', 'gateways' => ['broker'], 'service' => 'identity', 'upstream' => '/api/v1/users/',
            'input' => 'user_create_broker', 'response' => 'created_user'],
        'users.create_employer' => ['methods' => ['POST'], 'path' => 'users', 'gateways' => ['employer'], 'service' => 'identity', 'upstream' => '/api/v1/users/',
            'input' => 'user_create_employer', 'response' => 'created_user'],
    ],
];
