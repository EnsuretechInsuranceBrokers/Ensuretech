<?php

$ID = '[0-9]+';
$UUID = '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}';
$cfg = ['tpa_id' => ['to' => 'tpaId', 'type' => 'int', 'required' => true, 'min' => 1], 'adapter' => ['type' => 'string', 'required' => true, 'enum' => ['GENERIC_REST']],
    'base_url' => ['to' => 'baseUrl', 'type' => 'string', 'required' => true, 'max' => 300, 'pattern' => '#^https?://#i'],
    'auth_type' => ['to' => 'authType', 'type' => 'string', 'required' => true, 'enum' => ['NONE', 'BASIC', 'BEARER']],
    'secret_ref' => ['to' => 'secretRef', 'type' => 'string', 'pattern' => '/^[A-Z][A-Z0-9_]{2,40}$/'],
    'batch_size' => ['to' => 'batchSize', 'type' => 'int', 'min' => 1, 'max' => 1000], 'timeout_ms' => ['to' => 'timeoutMs', 'type' => 'int', 'min' => 100, 'max' => 60000],
    'enabled' => ['type' => 'bool']];

return [
    'schemas' => [
        // secretRef names an environment variable of the deployment: an internal detail, not shown
        'tpa_config' => ['id' => 'id', 'tpaId' => 'tpa_id', 'adapter' => 'adapter', 'baseUrl' => 'base_url', 'authType' => 'auth_type', 'batchSize' => 'batch_size',
            'timeoutMs' => 'timeout_ms', 'enabled' => 'enabled', 'updatedAt' => 'updated_at'],
        'sync_job_detail' => ['id' => 'id', 'endorsementJobId' => 'endorsement_id', 'policyId' => 'policy_id', 'employerId' => 'employer_id', 'tpaId' => 'tpa_id', 'opType' => 'op_type',
            'membersExpected' => 'members_expected', 'status' => 'status', 'attempts' => 'attempts', 'syncedCount' => 'synced', 'rejectedCount' => 'rejected', 'lastError' => 'last_error',
            'createdAt' => 'created_at', 'completedAt' => 'completed_at', 'rejections' => ['as' => 'rejections', 'list' => 'sync_rejection']],
        'sync_rejection' => ['memberId' => 'member_id', 'reason' => 'reason'],
        'integration_status' => ['circuitBreakers' => ['as' => 'circuit_breakers', 'raw' => true], 'deadLettered' => 'dead_lettered', 'pending' => 'pending'],
        'reconcile_run' => ['id' => 'id', 'policyId' => 'policy_id', 'tpaId' => 'tpa_id', 'status' => 'status', 'rosterSize' => 'roster_size', 'tpaRecords' => 'tpa_records',
            'matched' => 'matched', 'misses' => 'misses', 'rosterUnmatched' => 'roster_unmatched', 'error' => 'error'],
        'reconcile_miss' => ['employeeCode' => 'employee_code', 'relation' => 'relation', 'memberId' => 'tpa_member_id', 'reason' => 'reason'],
        'reconcile_detail' => ['run' => ['as' => 'run', 'schema' => 'reconcile_run'], 'misses' => ['as' => 'misses', 'list' => 'reconcile_miss']],
        'reconcile_settings' => ['stripLeadingZeros' => 'strip_leading_zeros', 'relationLabels' => ['as' => 'relation_labels', 'raw' => true]],
    ],
    'inputs' => [
        'tpa_config_in' => $cfg,
        'reconcile_settings_put' => ['strip_leading_zeros' => ['to' => 'stripLeadingZeros', 'type' => 'bool', 'required' => true],
            'relation_labels' => ['to' => 'relationLabels', 'type' => 'map', 'required' => true]],
    ],
    'endpoints' => [
        'tpa.configs' => ['methods' => ['GET'], 'path' => 'integration/tpa-configs', 'gateways' => ['broker'], 'service' => 'integration', 'upstream' => '/api/v1/broker/integration/tpa-configs',
            'response' => ['list' => 'tpa_config']],
        'tpa.configs_create' => ['methods' => ['POST'], 'path' => 'integration/tpa-configs', 'gateways' => ['broker'], 'service' => 'integration',
            'upstream' => '/api/v1/broker/integration/tpa-configs', 'input' => 'tpa_config_in', 'response' => 'tpa_config'],
        'tpa.configs_update' => ['methods' => ['PUT'], 'path' => 'integration/tpa-configs/{id}', 'gateways' => ['broker'], 'service' => 'integration',
            'upstream' => '/api/v1/broker/integration/tpa-configs/{id}', 'where' => ['id' => $ID], 'input' => 'tpa_config_in', 'response' => 'tpa_config'],
        'tpa.reconcile_settings' => ['methods' => ['PUT'], 'path' => 'integration/tpa-configs/{id}/reconciliation', 'gateways' => ['broker'], 'service' => 'integration',
            'upstream' => '/api/v1/broker/integration/tpa-configs/{id}/reconciliation', 'where' => ['id' => $ID], 'input' => 'reconcile_settings_put', 'response' => 'reconcile_settings'],
        'sync_jobs.get' => ['methods' => ['GET'], 'path' => 'integration/sync-jobs/{id}', 'gateways' => ['broker'], 'service' => 'integration',
            'upstream' => '/api/v1/broker/integration/sync-jobs/{id}', 'where' => ['id' => $UUID], 'response' => 'sync_job_detail'],
        'sync_jobs.retry' => ['methods' => ['POST'], 'path' => 'integration/sync-jobs/{id}/retry', 'gateways' => ['broker'], 'service' => 'integration',
            'upstream' => '/api/v1/broker/integration/sync-jobs/{id}/retry', 'where' => ['id' => $UUID], 'response' => 'sync_job_detail'],
        'tpa.status' => ['methods' => ['GET'], 'path' => 'integration/status', 'gateways' => ['broker'], 'service' => 'integration', 'upstream' => '/api/v1/broker/integration/status',
            'response' => 'integration_status'],
        'tpa.reconcile' => ['methods' => ['POST'], 'path' => 'integration/policies/{policy_id}/reconcile', 'gateways' => ['broker'], 'service' => 'integration', 'heavy' => true,
            'upstream' => '/api/v1/broker/integration/policies/{policy_id}/reconcile', 'where' => ['policy_id' => $ID], 'response' => 'reconcile_run'],
        'tpa.reconcile_runs' => ['methods' => ['GET'], 'path' => 'integration/reconcile-runs', 'gateways' => ['broker'], 'service' => 'integration',
            'upstream' => '/api/v1/broker/integration/reconcile-runs', 'query' => ['policy_id' => 'policyId'], 'response' => ['list' => 'reconcile_run']],
        'tpa.reconcile_run' => ['methods' => ['GET'], 'path' => 'integration/reconcile-runs/{id}', 'gateways' => ['broker'], 'service' => 'integration',
            'upstream' => '/api/v1/broker/integration/reconcile-runs/{id}', 'where' => ['id' => $UUID], 'response' => 'reconcile_detail'],
    ],
];
