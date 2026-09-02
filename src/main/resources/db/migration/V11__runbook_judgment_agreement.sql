ALTER TABLE runbook_relevance_judgment ADD reviewer_grade INT;

ALTER TABLE runbook_relevance_judgment
    ADD CONSTRAINT ck_runbook_judgment_reviewer_grade
        CHECK (reviewer_grade IS NULL OR reviewer_grade BETWEEN 0 AND 3);
