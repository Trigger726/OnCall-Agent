CREATE TABLE runbook_document (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stable_key VARCHAR(80) NOT NULL,
    version_no INT NOT NULL,
    status VARCHAR(24) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    service_code VARCHAR(80),
    title VARCHAR(200) NOT NULL,
    summary VARCHAR(1000),
    source_type VARCHAR(24) NOT NULL,
    source_name VARCHAR(255) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    markdown_content TEXT NOT NULL,
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_runbook_document_version UNIQUE (stable_key, version_no),
    CONSTRAINT fk_runbook_document_creator FOREIGN KEY (created_by) REFERENCES sys_user(id)
);

CREATE INDEX idx_runbook_document_status ON runbook_document(status, resource_type);
CREATE INDEX idx_runbook_document_content ON runbook_document(stable_key, content_hash);

CREATE TABLE runbook_document_acl (
    document_id BIGINT NOT NULL,
    role_code VARCHAR(32) NOT NULL,
    PRIMARY KEY (document_id, role_code),
    CONSTRAINT fk_runbook_acl_document FOREIGN KEY (document_id) REFERENCES runbook_document(id)
);

CREATE TABLE runbook_chunk (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    heading VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    char_count INT NOT NULL,
    CONSTRAINT uq_runbook_chunk_index UNIQUE (document_id, chunk_index),
    CONSTRAINT fk_runbook_chunk_document FOREIGN KEY (document_id) REFERENCES runbook_document(id)
);

CREATE INDEX idx_runbook_chunk_document ON runbook_chunk(document_id, chunk_index);

CREATE TABLE runbook_retrieval_eval_case (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_key VARCHAR(80) NOT NULL UNIQUE,
    query_text VARCHAR(500) NOT NULL,
    expected_stable_key VARCHAR(80) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE runbook_retrieval_eval_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    engine VARCHAR(40) NOT NULL,
    baseline_engine VARCHAR(40) NOT NULL,
    dataset_version VARCHAR(80) NOT NULL,
    case_count INT NOT NULL,
    baseline_recall_at_3 DECIMAL(8, 6) NOT NULL,
    baseline_mrr DECIMAL(8, 6) NOT NULL,
    recall_at_3 DECIMAL(8, 6) NOT NULL,
    mrr DECIMAL(8, 6) NOT NULL,
    citation_hit_rate DECIMAL(8, 6) NOT NULL,
    failures_json TEXT NOT NULL,
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_runbook_eval_creator FOREIGN KEY (created_by) REFERENCES sys_user(id)
);

INSERT INTO runbook_document (
    stable_key, version_no, status, resource_type, title, summary,
    source_type, source_name, content_hash, markdown_content, created_by, published_at
)
SELECT CONCAT('legacy-runbook-', id), 1, 'PUBLISHED', resource_type, title,
       CONCAT('由 V1 内置 Runbook 平滑迁移；症状关键词：', symptom_keyword),
       'LEGACY', 'V1__init_opspilot.sql',
       CASE id
           WHEN 1 THEN '18c01d48a2c1649a88e19bb2816a106cb7f4ed158f8c0eaa3749bbecc596fc7c'
           WHEN 2 THEN '56267f119845f2489a6a0e2974f88e8f0e51ff479dfbf518b9a34e5cc19811a7'
           ELSE '6fbb3bb52e3e1ed2951b8978f3faa6c0a793c9949d802b594f51ad53da17826e'
       END,
       content, 1, updated_at
FROM runbook
WHERE enabled = TRUE;

INSERT INTO runbook_chunk (document_id, chunk_index, heading, content, char_count)
SELECT id, 0, title, markdown_content, CHAR_LENGTH(markdown_content)
FROM runbook_document
WHERE source_type = 'LEGACY';

INSERT INTO runbook_document_acl (document_id, role_code)
SELECT id, 'ADMIN' FROM runbook_document WHERE source_type = 'LEGACY';
INSERT INTO runbook_document_acl (document_id, role_code)
SELECT id, 'OPS_MANAGER' FROM runbook_document WHERE source_type = 'LEGACY';
INSERT INTO runbook_document_acl (document_id, role_code)
SELECT id, 'ON_CALL' FROM runbook_document WHERE source_type = 'LEGACY';

INSERT INTO runbook_retrieval_eval_case (case_key, query_text, expected_stable_key) VALUES
    ('redis-pool-pending', '缓存客户端 active pending 慢命令连接排队', 'legacy-runbook-2'),
    ('auth-token-compat', 'token 格式兼容 错误码 发布批次', 'legacy-runbook-3'),
    ('api-timeout-dependency', '接口 P95 P99 下游依赖 两小时变更', 'legacy-runbook-1');
