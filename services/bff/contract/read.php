<?php

$UUID = '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}';
$both = ['broker', 'employer'];
$XLSX = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

return [
    'schemas' => [
        'read_job_history' => ['from_state' => 'from_status', 'to_state' => 'to_status', 'phase' => 'phase', 'category' => 'category', 'actor' => 'actor', 'ts' => 'at'],
        'read_job_detail' => ['id' => 'id', 'policy_id' => 'policy_id', 'employer_id' => 'employer_id', 'op_type' => 'op_type', 'state' => 'status', 'phase' => 'phase',
            'category' => 'category', 'is_terminal' => 'is_terminal', 'state_ts' => 'updated_at', 'history' => ['as' => 'history', 'list' => 'read_job_history']],
        'rag_answer' => ['answer' => 'answer', 'mode' => 'mode', 'advisory' => 'advisory', 'truncated' => 'truncated', 'citations' => ['as' => 'citations', 'list' => 'rag_citation']],
        'rag_citation' => ['n' => 'n', 'title' => 'title', 'source_type' => 'source_type', 'policy_id' => 'policy_id', 'score' => 'score'],
        'knowledge_doc' => ['id' => 'id', 'title' => 'title', 'kind' => 'kind', 'audience' => 'audience', 'employer_id' => 'employer_id', 'policy_id' => 'policy_id',
            'chars' => 'chars', 'created_at' => 'created_at'],
        'knowledge_created' => ['id' => 'id', 'chunks' => 'chunks'],
    ],
    'inputs' => [
        'rag_ask' => ['question' => ['type' => 'string', 'required' => true, 'min' => 1, 'max' => 2000], 'policy_id' => ['type' => 'int', 'min' => 1]],
        'knowledge_create' => ['title' => ['type' => 'string', 'required' => true, 'max' => 200], 'text' => ['type' => 'string', 'required' => true, 'max' => 200000],
            'kind' => ['type' => 'string', 'enum' => ['POLICY_DOC', 'TPA_RULES', 'NOTE']], 'audience' => ['type' => 'string', 'enum' => ['BROKER', 'EMPLOYER']],
            'employer_id' => ['type' => 'int', 'min' => 1], 'policy_id' => ['type' => 'int', 'min' => 1]],
    ],
    'endpoints' => [
        'read.job' => ['methods' => ['GET'], 'path' => 'activity/jobs/{id}', 'gateways' => $both, 'service' => 'read', 'upstream' => '/api/v1/{gw}/read/jobs/{id}/', 'where' => ['id' => $UUID],
            'response' => 'read_job_detail'],
        'reports.download' => ['methods' => ['GET'], 'path' => 'reports/{report}', 'gateways' => $both, 'service' => 'read', 'upstream' => '/api/v1/{gw}/read/reports/{report}/',
            'where' => ['report' => 'endorsements|policies|cd-balances|tpa-syncs'],
            'query' => ['file' => 'file', 'from' => 'from', 'to' => 'to', 'status' => 'status', 'state' => 'state', 'policy_id' => 'policy_id', 'op_type' => 'op_type',
                'is_terminal' => 'is_terminal', 'tpa_id' => 'tpa_id', 'low' => 'low'],
            'download' => ['types' => ['text/csv', $XLSX], 'max_bytes' => 100 * 1024 * 1024]],
        'rag.ask' => ['methods' => ['POST'], 'path' => 'assistant/ask', 'gateways' => $both, 'service' => 'read', 'upstream' => '/api/v1/{gw}/rag/ask/', 'input' => 'rag_ask',
            'heavy' => true, 'response' => 'rag_answer'],
        'rag.knowledge' => ['methods' => ['GET'], 'path' => 'assistant/knowledge', 'gateways' => $both, 'service' => 'read', 'upstream' => '/api/v1/{gw}/rag/knowledge/',
            'response' => ['list' => 'knowledge_doc']],
        'rag.knowledge_create' => ['methods' => ['POST'], 'path' => 'assistant/knowledge', 'gateways' => ['broker'], 'service' => 'read', 'upstream' => '/api/v1/broker/rag/knowledge/',
            'input' => 'knowledge_create', 'response' => 'knowledge_created'],
        'rag.knowledge_delete' => ['methods' => ['DELETE'], 'path' => 'assistant/knowledge/{id}', 'gateways' => ['broker'], 'service' => 'read', 'upstream' => '/api/v1/broker/rag/knowledge/{id}/',
            'where' => ['id' => '[0-9a-f]{32}'], 'response' => 'none'],
    ],
];
