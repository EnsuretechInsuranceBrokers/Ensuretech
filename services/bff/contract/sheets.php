<?php

// QA / demo tooling: it generates TEST DATA, so it is only exposed when BFF_QA_TOOLS is on (never in production).
$XLSX = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
$request = [
    'policy_id' => ['to' => 'policyId', 'type' => 'int', 'required' => true, 'min' => 1],
    'operation' => ['type' => 'string', 'required' => true, 'enum' => ['ADD', 'UPDATE', 'REMOVE']],
    'options' => ['type' => 'object', 'input' => 'sheet_options'],
    'source' => ['type' => 'list', 'of' => 'map', 'max' => 100000],
    'count' => ['type' => 'int', 'min' => 1, 'max' => 100000],
    'remove_mode' => ['to' => 'removeMode', 'type' => 'string', 'max' => 30],
    'code_prefix' => ['to' => 'codePrefix', 'type' => 'string', 'max' => 10, 'pattern' => '/^[A-Za-z0-9_-]*$/'],
    'seed' => ['type' => 'int'],
];

return [
    'feature' => 'qa_tools',
    'schemas' => [
        'sheet_spec' => ['__passthrough' => true],
        'sheet_generated' => ['operation' => 'operation', 'seed' => 'seed', 'columns' => ['as' => 'columns', 'raw' => true], 'rows' => ['as' => 'rows', 'raw' => true],
            'warnings' => ['as' => 'warnings', 'raw' => true], 'codes' => ['as' => 'codes', 'raw' => true], 'issues' => ['as' => 'issues', 'raw' => true]],
        'sheet_validated' => ['valid' => 'valid', 'issueCount' => 'issue_count', 'issues' => ['as' => 'issues', 'raw' => true]],
    ],
    'inputs' => [
        'sheet_options' => ['families' => ['type' => 'int', 'min' => 1, 'max' => 500000], 'spouse_pct' => ['to' => 'spousePct', 'type' => 'int', 'min' => 0, 'max' => 100],
            'child_pct' => ['to' => 'childPct', 'type' => 'int', 'min' => 0, 'max' => 100], 'twin_pct' => ['to' => 'twinPct', 'type' => 'int', 'min' => 0, 'max' => 100],
            'parent_pct' => ['to' => 'parentPct', 'type' => 'int', 'min' => 0, 'max' => 100], 'mid_term_pct' => ['to' => 'midTermPct', 'type' => 'int', 'min' => 0, 'max' => 100],
            'fill_optional' => ['to' => 'fillOptional', 'type' => 'bool'], 'include_premiums' => ['to' => 'includePremiums', 'type' => 'bool'],
            'effective_date' => ['to' => 'effectiveDate', 'type' => 'date'], 'sum_insured' => ['to' => 'sumInsured', 'type' => 'string', 'max' => 20],
            'flex_plan' => ['to' => 'flexPlan', 'type' => 'string', 'max' => 60], 'employee_premium' => ['to' => 'employeePremium', 'type' => 'string', 'max' => 20],
            'employer_premium' => ['to' => 'employerPremium', 'type' => 'string', 'max' => 20]],
        'sheet_generate' => $request,
        'sheet_validate' => ['policy_id' => $request['policy_id'], 'operation' => $request['operation'], 'rows' => ['type' => 'list', 'of' => 'map', 'required' => true, 'max' => 100000]],
    ],
    'endpoints' => [
        'sheets.spec' => ['methods' => ['GET'], 'path' => 'sheets/spec', 'gateways' => ['broker'], 'service' => 'sheets', 'upstream' => '/api/v1/broker/sheets/spec',
            'query' => ['policy_id' => 'policyId'], 'response' => 'sheet_spec', 'feature' => 'qa_tools'],
        'sheets.generate' => ['methods' => ['POST'], 'path' => 'sheets/generate', 'gateways' => ['broker'], 'service' => 'sheets', 'upstream' => '/api/v1/broker/sheets/generate',
            'input' => 'sheet_generate', 'heavy' => true, 'response' => 'sheet_generated', 'feature' => 'qa_tools'],
        'sheets.file' => ['methods' => ['POST'], 'path' => 'sheets/generate/file', 'gateways' => ['broker'], 'service' => 'sheets', 'upstream' => '/api/v1/broker/sheets/generate/file',
            'query' => ['format' => 'format'], 'input' => 'sheet_generate', 'heavy' => true, 'download' => ['types' => ['text/csv', $XLSX], 'max_bytes' => 200 * 1024 * 1024],
            'feature' => 'qa_tools'],
        'sheets.validate' => ['methods' => ['POST'], 'path' => 'sheets/validate', 'gateways' => ['broker'], 'service' => 'sheets', 'upstream' => '/api/v1/broker/sheets/validate',
            'input' => 'sheet_validate', 'heavy' => true, 'response' => 'sheet_validated', 'feature' => 'qa_tools'],
    ],
];
