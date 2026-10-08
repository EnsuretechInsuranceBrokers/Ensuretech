<?php

$ID = '[0-9]+';

return [
    'schemas' => [
        'cd_pool_account' => ['accountId' => 'account_id', 'employerId' => 'employer_id', 'balance' => 'balance', 'reserved' => 'reserved', 'available' => 'available',
            'thresholdAmount' => 'threshold_amount', 'belowThreshold' => 'below_threshold', 'accountType' => 'account_type', 'name' => 'name'],
        'cd_pool' => ['account' => ['as' => 'account', 'schema' => 'cd_pool_account'], 'policyIds' => ['as' => 'policy_ids', 'raw' => true]],
    ],
    'inputs' => [
        'cd_pool_create' => ['type' => ['type' => 'string', 'required' => true, 'enum' => ['GROUP', 'COMPANY', 'MULTIPLE']], 'name' => ['type' => 'string', 'required' => true, 'max' => 120],
            'employer_id' => ['to' => 'employerId', 'type' => 'int', 'min' => 1], 'policy_ids' => ['to' => 'policyIds', 'type' => 'list', 'of' => 'int', 'max' => 500]],
        'cd_pool_policies' => ['policy_ids' => ['to' => 'policyIds', 'type' => 'list', 'of' => 'int', 'required' => true, 'max' => 500]],
        'cd_threshold' => ['threshold_amount' => ['to' => 'thresholdAmount', 'type' => 'number', 'required' => true, 'min' => 0]],
        'cd_decision' => ['note' => ['type' => 'string', 'max' => 500]],
    ],
    'endpoints' => [
        'cd.pools_create' => ['methods' => ['POST'], 'path' => 'cd/pools', 'gateways' => ['broker'], 'service' => 'billing', 'upstream' => '/api/v1/broker/cd/pools',
            'input' => 'cd_pool_create', 'response' => 'cd_pool'],
        'cd.pools_get' => ['methods' => ['GET'], 'path' => 'cd/pools/{id}', 'gateways' => ['broker'], 'service' => 'billing', 'upstream' => '/api/v1/broker/cd/pools/{id}',
            'where' => ['id' => $ID], 'response' => 'cd_pool'],
        'cd.pools_add' => ['methods' => ['POST'], 'path' => 'cd/pools/{id}/policies', 'gateways' => ['broker'], 'service' => 'billing', 'upstream' => '/api/v1/broker/cd/pools/{id}/policies',
            'where' => ['id' => $ID], 'input' => 'cd_pool_policies', 'response' => 'cd_pool'],
        'cd.pools_remove' => ['methods' => ['DELETE'], 'path' => 'cd/pools/{id}/policies/{policy_id}', 'gateways' => ['broker'], 'service' => 'billing',
            'upstream' => '/api/v1/broker/cd/pools/{id}/policies/{policy_id}', 'where' => ['id' => $ID, 'policy_id' => $ID], 'response' => 'cd_pool'],
        'cd.threshold' => ['methods' => ['PUT'], 'path' => 'cd/accounts/{policy_id}/threshold', 'gateways' => ['broker'], 'service' => 'billing',
            'upstream' => '/api/v1/broker/cd/accounts/{policy_id}/threshold', 'where' => ['policy_id' => $ID], 'input' => 'cd_threshold', 'response' => 'cd_account'],
        'cd.replenishments' => ['methods' => ['GET'], 'path' => 'cd/accounts/{policy_id}/replenishments', 'gateways' => ['broker', 'employer'], 'service' => 'billing',
            'upstream' => '/api/v1/{gw}/cd/accounts/{policy_id}/replenishments', 'where' => ['policy_id' => $ID], 'response' => ['list' => 'replenishment']],
        'cd.replenishment_approve' => ['methods' => ['POST'], 'path' => 'cd/accounts/{policy_id}/replenishments/{id}/approve', 'gateways' => ['broker'], 'service' => 'billing',
            'upstream' => '/api/v1/broker/cd/accounts/{policy_id}/replenishments/{id}/approve', 'where' => ['policy_id' => $ID, 'id' => $ID], 'response' => 'replenishment'],
        'cd.replenishment_reject' => ['methods' => ['POST'], 'path' => 'cd/accounts/{policy_id}/replenishments/{id}/reject', 'gateways' => ['broker'], 'service' => 'billing',
            'upstream' => '/api/v1/broker/cd/accounts/{policy_id}/replenishments/{id}/reject', 'where' => ['policy_id' => $ID, 'id' => $ID], 'input' => 'cd_decision', 'response' => 'replenishment'],
    ],
];
