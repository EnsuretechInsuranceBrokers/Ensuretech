<?php

$ID = '[0-9]+';
$gstin = '/^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$/';
$pan = '/^[A-Z]{5}[0-9]{4}[A-Z]$/';
$employerFields = [
    'legal_name' => ['to' => 'legalName', 'type' => 'string', 'max' => 200], 'display_name' => ['to' => 'displayName', 'type' => 'string', 'max' => 120],
    'gstin' => ['type' => 'string', 'pattern' => $gstin], 'pan' => ['type' => 'string', 'pattern' => $pan], 'sector' => ['type' => 'string', 'max' => 80],
    'employee_count' => ['to' => 'employeeCount', 'type' => 'int', 'min' => 0], 'contact_email' => ['to' => 'contactEmail', 'type' => 'string', 'max' => 254, 'pattern' => '/^[^@\s]+@[^@\s]+$/'],
    'contact_phone' => ['to' => 'contactPhone', 'type' => 'string', 'max' => 20], 'hrms_type' => ['to' => 'hrmsType', 'type' => 'string', 'max' => 40], 'hidden' => ['type' => 'bool'],
];

return [
    'schemas' => [
        'branch' => ['id' => 'id', 'branchCode' => 'code', 'city' => 'city', 'state' => 'state', 'headOffice' => 'is_head_office'],
        'employer_config' => ['key' => 'key', 'value' => 'value', 'expiresAt' => 'expires_at'],
    ],
    'inputs' => [
        'employer_create' => [
            'code' => ['to' => 'employerCode', 'type' => 'string', 'required' => true, 'max' => 40, 'pattern' => '/^[A-Za-z0-9_-]+$/'],
            'legal_name' => ['to' => 'legalName', 'type' => 'string', 'required' => true, 'max' => 200], 'display_name' => ['to' => 'displayName', 'type' => 'string', 'required' => true, 'max' => 120],
            'parent_employer_id' => ['to' => 'parentEmployerId', 'type' => 'int', 'min' => 1]] + array_diff_key($employerFields, ['legal_name' => 1, 'display_name' => 1]),
        'employer_update' => $employerFields,
        'employer_parent' => ['parent_employer_id' => ['to' => 'parentEmployerId', 'type' => 'int', 'required' => true, 'min' => 1]],
        'employer_config_put' => ['value' => ['type' => 'string', 'required' => true, 'max' => 2000], 'expires_at' => ['to' => 'expiresAt', 'type' => 'datetime']],
        'branch_create' => ['code' => ['to' => 'branchCode', 'type' => 'string', 'required' => true, 'max' => 20], 'city' => ['type' => 'string', 'max' => 80],
            'state' => ['type' => 'string', 'max' => 80], 'is_head_office' => ['to' => 'headOffice', 'type' => 'bool']],
    ],
    'endpoints' => [
        'employers.create' => ['methods' => ['POST'], 'path' => 'employers', 'gateways' => ['broker'], 'service' => 'organization', 'upstream' => '/api/v1/broker/employers',
            'input' => 'employer_create', 'response' => 'employer'],
        'employers.update' => ['methods' => ['PATCH'], 'path' => 'employers/{id}', 'gateways' => ['broker'], 'service' => 'organization', 'upstream' => '/api/v1/broker/employers/{id}',
            'where' => ['id' => $ID], 'input' => 'employer_update', 'response' => 'employer'],
        'employers.deactivate' => ['methods' => ['DELETE'], 'path' => 'employers/{id}', 'gateways' => ['broker'], 'service' => 'organization', 'upstream' => '/api/v1/broker/employers/{id}',
            'where' => ['id' => $ID], 'response' => 'none'],
        'employers.set_parent' => ['methods' => ['PUT'], 'path' => 'employers/{id}/parent', 'gateways' => ['broker'], 'service' => 'organization',
            'upstream' => '/api/v1/broker/employers/{id}/parent', 'where' => ['id' => $ID], 'input' => 'employer_parent', 'response' => 'employer'],
        'employers.clear_parent' => ['methods' => ['DELETE'], 'path' => 'employers/{id}/parent', 'gateways' => ['broker'], 'service' => 'organization',
            'upstream' => '/api/v1/broker/employers/{id}/parent', 'where' => ['id' => $ID], 'response' => 'employer'],
        'employers.configs' => ['methods' => ['GET'], 'path' => 'employers/{id}/configs', 'gateways' => ['broker'], 'service' => 'organization',
            'upstream' => '/api/v1/broker/employers/{id}/configs', 'where' => ['id' => $ID], 'response' => ['list' => 'employer_config']],
        'employers.config_put' => ['methods' => ['PUT'], 'path' => 'employers/{id}/configs/{key}', 'gateways' => ['broker'], 'service' => 'organization',
            'upstream' => '/api/v1/broker/employers/{id}/configs/{key}', 'where' => ['id' => $ID, 'key' => '[A-Za-z0-9_.-]{1,80}'], 'input' => 'employer_config_put', 'response' => 'employer_config'],
        'branches.list' => ['methods' => ['GET'], 'path' => 'branches', 'gateways' => ['broker'], 'service' => 'organization', 'upstream' => '/api/v1/broker/branches',
            'response' => ['list' => 'branch']],
        'branches.create' => ['methods' => ['POST'], 'path' => 'branches', 'gateways' => ['broker'], 'service' => 'organization', 'upstream' => '/api/v1/broker/branches',
            'input' => 'branch_create', 'response' => 'branch'],
    ],
];
