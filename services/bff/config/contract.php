<?php

/*
 | THE PUBLIC API CONTRACT. Every endpoint a client can call and every field it can see is listed here, nowhere else. Adding a field a service
 | returns does not expose it; adding an endpoint a service has does not expose it. Public naming is snake_case, pages are
 | {items, total, page (1-based), page_size}, jobs use `status`, and ids that only mean something inside the platform (broker_id, status_code, ...) are not exposed.
 |
 | endpoint: methods, path (under /api/v1/{gateway}/), gateways, service, upstream ({gw} = the caller's gateway), query (public => upstream name, or
 |           [upstream name, add]), body (allowed JSON keys; everything else is dropped), response (schema | [list => schema]), where (route constraints).
 */
$ID = '[0-9]+';
$UUID = '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}';

// pagination parameters, per backend dialect
$springQuery = ['page' => ['page', -1], 'page_size' => 'size'];     // Spring pages are 0-based and call the size `size`
$djangoQuery = ['page' => 'page', 'page_size' => 'page_size'];

$base = [
    'schemas' => [
        // ── pages (one public shape, three backend dialects)
        'spring_page' => ['content' => ['as' => 'items', 'list' => '@item'], 'totalElements' => 'total', 'number' => ['as' => 'page', 'add' => 1], 'size' => 'page_size'],
        'django_page' => ['results' => ['as' => 'items', 'list' => '@item'], 'count' => 'total', 'page' => 'page', 'page_size' => 'page_size'],

        'employer' => ['id' => 'id', 'employerCode' => 'code', 'legalName' => 'legal_name', 'displayName' => 'display_name', 'gstin' => 'gstin', 'pan' => 'pan',
            'sector' => 'sector', 'employeeCount' => 'employee_count', 'contactEmail' => 'contact_email', 'contactPhone' => 'contact_phone',
            'hrmsType' => 'hrms_type', 'active' => 'active', 'hidden' => 'hidden', 'parentEmployerId' => 'parent_employer_id'],

        'policy' => ['id' => 'id', 'employerId' => 'employer_id', 'policyNumber' => 'policy_number', 'insurerId' => 'insurer_id', 'tpaId' => 'tpa_id',
            'policyType' => 'policy_type', 'policySubType' => 'policy_sub_type', 'status' => 'status', 'startDate' => 'start_date', 'endDate' => 'end_date',
            'renewalDate' => 'renewal_date', 'maxMembers' => 'max_members', 'inception' => 'is_inception', 'proRataType' => 'pro_rata_type'],

        'cd_account' => ['policyId' => 'policy_id', 'employerId' => 'employer_id', 'balance' => 'balance', 'reserved' => 'reserved', 'available' => 'available',
            'thresholdAmount' => 'threshold_amount', 'belowThreshold' => 'below_threshold', 'accountType' => 'account_type', 'name' => 'name'],
        'cd_entry' => ['id' => 'id', 'entryType' => 'entry_type', 'amount' => 'amount', 'balanceAfter' => 'balance_after', 'referenceType' => 'reference_type',
            'referenceId' => 'reference', 'narration' => 'narration', 'createdBy' => 'created_by', 'createdAt' => 'created_at'],
        'replenishment' => ['id' => 'id', 'amount' => 'amount', 'reference' => 'reference', 'status' => 'status', 'requestedBy' => 'requested_by',
            'decidedBy' => 'decided_by', 'decisionNote' => 'decision_note', 'createdAt' => 'created_at', 'decidedAt' => 'decided_at'],

        'job' => ['id' => 'id', 'policy_id' => 'policy_id', 'employer_id' => 'employer_id', 'source_type' => 'source_type', 'op_type' => 'op_type', 'status' => 'status',
            'phase' => 'phase', 'category' => 'category', 'is_terminal' => 'is_terminal', 'source_filename' => 'source_filename', 'effective_date' => 'effective_date',
            'total_rows' => 'total_rows', 'failed_rows' => 'failed_rows', 'chunk_count' => 'chunk_count', 'chunks_completed' => 'chunks_completed',
            'total_premium' => 'total_premium', 'tax_amount' => 'tax_amount', 'premium_delta' => 'premium_delta', 'cd_status' => 'cd_status',
            'error_summary' => ['as' => 'error_summary', 'raw' => true], 'created_at' => 'created_at', 'started_at' => 'started_at', 'completed_at' => 'completed_at'],
        'job_audit' => ['from_state' => 'from_status', 'to_state' => 'to_status', 'phase' => 'phase', 'category' => 'category', 'actor' => 'actor',
            'duration_ms' => 'duration_ms', 'transitioned_at' => 'at'],
        'progress' => ['job_id' => 'job_id', 'state' => 'status', 'phase' => 'phase', 'category' => 'category', 'percent' => 'percent', 'total_rows' => 'total_rows',
            'failed_rows' => 'failed_rows', 'chunk_count' => 'chunk_count', 'chunks_validated' => 'chunks_validated', 'chunks_priced' => 'chunks_priced',
            'chunks_endorsed' => 'chunks_endorsed'],

        // read-service projections
        'read_job' => ['id' => 'id', 'policy_id' => 'policy_id', 'employer_id' => 'employer_id', 'op_type' => 'op_type', 'state' => 'status', 'phase' => 'phase',
            'category' => 'category', 'is_terminal' => 'is_terminal', 'state_ts' => 'updated_at'],
        'read_cd_account' => ['id' => 'policy_id', 'employer_id' => 'employer_id', 'balance' => 'balance', 'reserved' => 'reserved', 'available' => 'available',
            'threshold' => 'threshold_amount', 'low' => 'is_low', 'account_type' => 'account_type', 'updated_at' => 'updated_at'],
        'read_dashboard' => ['jobs' => ['as' => 'jobs', 'schema' => 'read_dashboard_jobs'], 'cd' => ['as' => 'cd', 'schema' => 'read_dashboard_cd'],
            'tpa_syncs' => ['as' => 'tpa_syncs', 'schema' => 'read_dashboard_syncs']],
        'read_dashboard_jobs' => ['total' => 'total', 'open' => 'open', 'by_state' => ['as' => 'by_status', 'raw' => true]],
        'read_dashboard_cd' => ['accounts' => 'accounts', 'low' => 'low'],
        'read_dashboard_syncs' => ['failed' => 'failed', 'completed' => 'completed'],
        'red_flags' => ['count' => 'count', 'pending_days' => 'pending_days', 'flags' => ['as' => 'flags', 'list' => 'red_flag']],
        'red_flag' => ['type' => 'type', 'priority' => 'priority', 'policy_id' => 'policy_id', 'job_id' => 'job_id', 'sync_id' => 'sync_id', 'detail' => 'detail'],
        'activity' => ['months' => ['as' => 'months', 'list' => 'activity_month']],
        'activity_month' => ['month' => 'month', 'added' => 'added', 'updated' => 'updated', 'removed' => 'removed', 'endorsements' => 'endorsements'],

        'sync_job' => ['id' => 'id', 'endorsementJobId' => 'endorsement_id', 'policyId' => 'policy_id', 'employerId' => 'employer_id', 'tpaId' => 'tpa_id',
            'opType' => 'op_type', 'membersExpected' => 'members_expected', 'status' => 'status', 'attempts' => 'attempts', 'syncedCount' => 'synced',
            'rejectedCount' => 'rejected', 'lastError' => 'last_error', 'createdAt' => 'created_at', 'completedAt' => 'completed_at'],
    ],

    // a page's `@item` stands for the item schema named by the endpoint
    'endpoints' => [
        'employers.list' => ['methods' => ['GET'], 'path' => 'employers', 'gateways' => ['broker'], 'service' => 'organization', 'upstream' => '/api/v1/broker/employers',
            'query' => $springQuery, 'response' => ['page' => 'spring_page', 'item' => 'employer']],
        'employers.get' => ['methods' => ['GET'], 'path' => 'employers/{id}', 'gateways' => ['broker'], 'service' => 'organization', 'upstream' => '/api/v1/broker/employers/{id}',
            'where' => ['id' => $ID], 'response' => 'employer'],
        'employers.children' => ['methods' => ['GET'], 'path' => 'employers/{id}/children', 'gateways' => ['broker'], 'service' => 'organization',
            'upstream' => '/api/v1/broker/employers/{id}/children', 'where' => ['id' => $ID], 'response' => ['list' => 'employer']],
        'employer.me' => ['methods' => ['GET'], 'path' => 'employer', 'gateways' => ['employer'], 'service' => 'organization', 'upstream' => '/api/v1/employer/employer', 'response' => 'employer'],
        'employer.children' => ['methods' => ['GET'], 'path' => 'employer/children', 'gateways' => ['employer'], 'service' => 'organization',
            'upstream' => '/api/v1/employer/employer/children', 'response' => ['list' => 'employer']],

        'policies.list' => ['methods' => ['GET'], 'path' => 'policies', 'gateways' => ['broker', 'employer'], 'service' => 'policy', 'upstream' => '/api/v1/{gw}/policies',
            'query' => $springQuery + ['employer_id' => 'employerId'], 'response' => ['page' => 'spring_page', 'item' => 'policy']],
        'policies.get' => ['methods' => ['GET'], 'path' => 'policies/{id}', 'gateways' => ['broker', 'employer'], 'service' => 'policy', 'upstream' => '/api/v1/{gw}/policies/{id}',
            'where' => ['id' => $ID], 'response' => 'policy'],

        'cd.accounts' => ['methods' => ['GET'], 'path' => 'cd/accounts', 'gateways' => ['broker', 'employer'], 'service' => 'billing', 'upstream' => '/api/v1/{gw}/cd/accounts',
            'response' => ['list' => 'cd_account']],
        'cd.account' => ['methods' => ['GET'], 'path' => 'cd/accounts/{policy_id}', 'gateways' => ['broker', 'employer'], 'service' => 'billing',
            'upstream' => '/api/v1/{gw}/cd/accounts/{policy_id}', 'where' => ['policy_id' => $ID], 'response' => 'cd_account'],
        'cd.statements' => ['methods' => ['GET'], 'path' => 'cd/accounts/{policy_id}/statements', 'gateways' => ['broker', 'employer'], 'service' => 'billing',
            'upstream' => '/api/v1/{gw}/cd/accounts/{policy_id}/statements', 'where' => ['policy_id' => $ID], 'query' => $springQuery,
            'response' => ['page' => 'spring_page', 'item' => 'cd_entry']],
        'cd.credit' => ['methods' => ['POST'], 'path' => 'cd/accounts/{policy_id}/credits', 'gateways' => ['broker'], 'service' => 'billing',
            'upstream' => '/api/v1/broker/cd/accounts/{policy_id}/credits', 'where' => ['policy_id' => $ID], 'body' => ['amount', 'reference', 'type', 'narration'],
            'headers' => ['Idempotency-Key'], 'response' => 'cd_entry'],
        'cd.replenish' => ['methods' => ['POST'], 'path' => 'cd/accounts/{policy_id}/replenishments', 'gateways' => ['broker', 'employer'], 'service' => 'billing',
            'upstream' => '/api/v1/{gw}/cd/accounts/{policy_id}/replenishments', 'where' => ['policy_id' => $ID], 'body' => ['amount', 'reference'], 'response' => 'replenishment'],

        'endorsements.list' => ['methods' => ['GET'], 'path' => 'endorsements', 'gateways' => ['broker', 'employer'], 'service' => 'endorsement',
            'upstream' => '/api/v1/{gw}/endorsements/', 'query' => $djangoQuery + ['status' => 'status', 'policy_id' => 'policy_id', 'op_type' => 'op_type'],
            'response' => ['page' => 'django_page', 'item' => 'job']],
        'endorsements.get' => ['methods' => ['GET'], 'path' => 'endorsements/{id}', 'gateways' => ['broker', 'employer'], 'service' => 'endorsement',
            'upstream' => '/api/v1/{gw}/endorsements/{id}/', 'where' => ['id' => $UUID], 'response' => 'job'],
        'endorsements.audit' => ['methods' => ['GET'], 'path' => 'endorsements/{id}/audit', 'gateways' => ['broker', 'employer'], 'service' => 'endorsement',
            'upstream' => '/api/v1/{gw}/endorsements/{id}/audit/', 'where' => ['id' => $UUID], 'response' => ['list' => 'job_audit']],
        'endorsements.upload' => ['methods' => ['POST'], 'path' => 'endorsements/csv/upload', 'gateways' => ['broker', 'employer'], 'service' => 'endorsement',
            'upstream' => '/api/v1/{gw}/endorsements/csv/upload/', 'multipart' => ['file' => 'file', 'fields' => ['policy_id', 'op_type', 'effective_date', 'mapping_id']],
            'headers' => ['Idempotency-Key'], 'heavy' => true, 'response' => 'job', 'append' => ['progress_url' => '/api/v1/{gw}/endorsements/{id}/progress']],
        'endorsements.cancel' => ['methods' => ['POST'], 'path' => 'endorsements/{id}/cancel', 'gateways' => ['broker', 'employer'], 'service' => 'endorsement',
            'upstream' => '/api/v1/{gw}/endorsements/{id}/cancel/', 'where' => ['id' => $UUID], 'response' => 'job'],
        'endorsements.proceed' => ['methods' => ['POST'], 'path' => 'endorsements/{id}/proceed', 'gateways' => ['broker', 'employer'], 'service' => 'endorsement',
            'upstream' => '/api/v1/{gw}/endorsements/{id}/proceed/', 'where' => ['id' => $UUID], 'response' => 'job'],
        'endorsements.retry' => ['methods' => ['POST'], 'path' => 'endorsements/{id}/retry', 'gateways' => ['broker', 'employer'], 'service' => 'endorsement',
            'upstream' => '/api/v1/{gw}/endorsements/{id}/retry/', 'where' => ['id' => $UUID], 'response' => 'job'],
        'endorsements.close' => ['methods' => ['POST'], 'path' => 'endorsements/{id}/close', 'gateways' => ['broker', 'employer'], 'service' => 'endorsement',
            'upstream' => '/api/v1/{gw}/endorsements/{id}/close/', 'where' => ['id' => $UUID], 'body' => ['reason'], 'response' => 'job'],
        'endorsements.progress' => ['methods' => ['GET'], 'path' => 'endorsements/{id}/progress', 'gateways' => ['broker', 'employer'], 'service' => 'endorsement',
            'upstream' => '/api/v1/{gw}/endorsements/{id}/progress/', 'where' => ['id' => $UUID], 'stream' => 'progress'],

        'read.dashboard' => ['methods' => ['GET'], 'path' => 'dashboard', 'gateways' => ['broker', 'employer'], 'service' => 'read', 'upstream' => '/api/v1/{gw}/read/dashboard/',
            'response' => 'read_dashboard'],
        'read.red_flags' => ['methods' => ['GET'], 'path' => 'dashboard/red-flags', 'gateways' => ['broker', 'employer'], 'service' => 'read',
            'upstream' => '/api/v1/{gw}/read/dashboard/red-flags/', 'query' => ['pending_days' => 'pending_days'], 'response' => 'red_flags'],
        'read.activity' => ['methods' => ['GET'], 'path' => 'dashboard/endorsement-activity', 'gateways' => ['broker', 'employer'], 'service' => 'read',
            'upstream' => '/api/v1/{gw}/read/dashboard/endorsement-activity/', 'query' => ['policy_id' => 'policy_id', 'from' => 'from', 'to' => 'to'], 'response' => 'activity'],
        'read.jobs' => ['methods' => ['GET'], 'path' => 'activity/jobs', 'gateways' => ['broker', 'employer'], 'service' => 'read', 'upstream' => '/api/v1/{gw}/read/jobs/',
            'query' => $djangoQuery + ['status' => 'state', 'policy_id' => 'policy_id', 'op_type' => 'op_type', 'is_terminal' => 'is_terminal'],
            'response' => ['page' => 'django_page', 'item' => 'read_job']],
        'read.cd_balances' => ['methods' => ['GET'], 'path' => 'activity/cd-balances', 'gateways' => ['broker', 'employer'], 'service' => 'read', 'upstream' => '/api/v1/{gw}/read/cd/',
            'query' => $djangoQuery + ['low' => 'low'], 'response' => ['page' => 'django_page', 'item' => 'read_cd_account']],

        'sync_jobs.list' => ['methods' => ['GET'], 'path' => 'integration/sync-jobs', 'gateways' => ['broker'], 'service' => 'integration',
            'upstream' => '/api/v1/broker/integration/sync-jobs', 'query' => $springQuery + ['status' => 'status', 'policy_id' => 'policyId'],
            'response' => ['page' => 'spring_page', 'item' => 'sync_job']],
    ],

    // Composed views: one public call, several services. Defined in App\Http\Controllers\OverviewController.
    'compositions' => ['policies.overview' => ['path' => 'policies/{id}/overview', 'gateways' => ['broker', 'employer'], 'where' => ['id' => $ID]]],
];

// The rest of the contract lives in contract/*.php (one file per area). Schemas, inputs and endpoints are merged; a name defined twice is a bug.
foreach (glob(base_path('contract/*.php')) as $file) {
    $part = require $file;
    foreach (['schemas', 'inputs', 'endpoints'] as $section) {
        foreach ($part[$section] ?? [] as $key => $def) {
            if (isset($base[$section][$key])) {
                throw new LogicException("contract: $section '$key' is defined twice ($file)");
            }
            $base[$section][$key] = isset($part['feature']) && $section === 'endpoints' ? $def + ['feature' => $part['feature']] : $def;
        }
    }
}

return $base;
