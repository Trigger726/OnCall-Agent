ALTER TABLE runbook_retrieval_query ADD scope_type VARCHAR(16) NOT NULL DEFAULT 'GLOBAL';
ALTER TABLE runbook_retrieval_query ADD scope_json TEXT;
-- Earlier ASSISTANT rows cannot prove whether a session was bound. Never infer scope from returned documents.
UPDATE runbook_retrieval_query SET scope_type = 'UNKNOWN' WHERE source_type = 'ASSISTANT';

ALTER TABLE runbook_retrieval_eval_case ADD scope_type VARCHAR(16) NOT NULL DEFAULT 'GLOBAL';
ALTER TABLE runbook_retrieval_eval_case ADD scope_json TEXT;
-- Preserve old labels and enabled flags, but mark ambiguous promoted labels for explicit exclusion by evaluation.
UPDATE runbook_retrieval_eval_case SET scope_type = 'UNKNOWN'
WHERE judgment_id IN (
    SELECT j.id FROM runbook_relevance_judgment j
    JOIN runbook_retrieval_query q ON q.id = j.search_id WHERE q.scope_type = 'UNKNOWN'
);
