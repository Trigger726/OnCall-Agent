ALTER TABLE runbook_retrieval_eval_case
    ADD relevance_grade INT NOT NULL DEFAULT 3;

UPDATE runbook_retrieval_eval_case
SET relevance_grade = (
    SELECT j.relevance_grade
    FROM runbook_relevance_judgment j
    WHERE j.id = runbook_retrieval_eval_case.judgment_id
)
WHERE judgment_id IS NOT NULL;

ALTER TABLE runbook_retrieval_eval_case
    ADD CONSTRAINT ck_runbook_eval_relevance_grade CHECK (relevance_grade BETWEEN 1 AND 3);

ALTER TABLE runbook_retrieval_eval_run ADD judgment_count INT;
ALTER TABLE runbook_retrieval_eval_run ADD baseline_ndcg_at_3 DECIMAL(8, 6);
ALTER TABLE runbook_retrieval_eval_run ADD ndcg_at_3 DECIMAL(8, 6);
