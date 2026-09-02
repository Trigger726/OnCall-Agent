CREATE TABLE runbook_chunk_embedding (
    chunk_id BIGINT NOT NULL,
    provider VARCHAR(40) NOT NULL,
    model VARCHAR(100) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    dimensions INT NOT NULL,
    embedding_json TEXT NOT NULL,
    indexed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (chunk_id, provider, model),
    CONSTRAINT fk_runbook_embedding_chunk FOREIGN KEY (chunk_id) REFERENCES runbook_chunk(id)
);

CREATE INDEX idx_runbook_embedding_model ON runbook_chunk_embedding(provider, model);

CREATE TABLE runbook_embedding_index_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider VARCHAR(40) NOT NULL,
    model VARCHAR(100) NOT NULL,
    status VARCHAR(24) NOT NULL,
    content_fingerprint VARCHAR(64) NOT NULL,
    chunk_count INT NOT NULL,
    dimensions INT,
    error_code VARCHAR(80),
    created_by BIGINT,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    CONSTRAINT fk_runbook_embedding_run_creator FOREIGN KEY (created_by) REFERENCES sys_user(id)
);

CREATE INDEX idx_runbook_embedding_run_latest
    ON runbook_embedding_index_run(provider, model, status, started_at);

ALTER TABLE runbook_retrieval_eval_run ADD comparison_json TEXT;

INSERT INTO runbook_document (
    stable_key, version_no, status, resource_type, service_code, title, summary,
    source_type, source_name, content_hash, markdown_content, created_by
) VALUES
    ('kafka-consumer-lag', 1, 'PUBLISHED', 'MIDDLEWARE', 'KAFKA-ORDER',
     'Kafka Consumer Lag 处置', '消费积压、再均衡与位点提交排查',
     'MARKDOWN', 'seed/runbooks/kafka-consumer-lag.md',
     'd10eda4033f969a72ae8043649602956a635127c23532b23ea44d8d407654257',
     '# Kafka Consumer Lag 处置 Kafka consumer lag、topic backlog、partition 消费落后时，检查 consumer group、rebalance、offset commit、处理耗时与分区倾斜；扩容前确认幂等，恢复后观察 lag 持续下降。', 1),
    ('mysql-connection-saturation', 1, 'PUBLISHED', 'DATABASE', 'MYSQL-CORE',
     'MySQL 连接饱和处置', '连接打满、连接池等待与慢事务排查',
     'MARKDOWN', 'seed/runbooks/mysql-connection-saturation.md',
     'b1bc1c2277021b3a422e078ce7c2829faf56aa9d5313ff82aa5be769d71ab8d8',
     '# MySQL 连接饱和处置 MySQL active connections、connection pool pending、too many connections 或 wait timeout 时，检查连接泄漏、慢 SQL、事务持有和 max_connections；优先限流或回滚异常版本。', 1),
    ('kubernetes-crashloop', 1, 'PUBLISHED', 'APPLICATION', 'K8S-PLATFORM',
     'Kubernetes CrashLoopBackOff 处置', '容器反复重启、OOM 与探针失败排查',
     'MARKDOWN', 'seed/runbooks/kubernetes-crashloop.md',
     'bbb29e9664ace82f2b0829a841b2d75d3eb3fbe36411d538a4e8c22581d9269f',
     '# Kubernetes CrashLoopBackOff 处置 Pod 反复重启、CrashLoopBackOff、OOMKilled、liveness probe 失败时，检查 previous logs、退出码、资源限制、探针和最近镜像变更；回滚后观察重启次数。', 1);

INSERT INTO runbook_chunk (document_id, chunk_index, heading, content, char_count)
SELECT id, 0, title, markdown_content, CHAR_LENGTH(markdown_content)
FROM runbook_document
WHERE stable_key IN ('kafka-consumer-lag', 'mysql-connection-saturation', 'kubernetes-crashloop')
  AND version_no = 1;

INSERT INTO runbook_document_acl (document_id, role_code)
SELECT id, 'ADMIN' FROM runbook_document
WHERE stable_key IN ('kafka-consumer-lag', 'mysql-connection-saturation', 'kubernetes-crashloop');
INSERT INTO runbook_document_acl (document_id, role_code)
SELECT id, 'OPS_MANAGER' FROM runbook_document
WHERE stable_key IN ('kafka-consumer-lag', 'mysql-connection-saturation', 'kubernetes-crashloop');
INSERT INTO runbook_document_acl (document_id, role_code)
SELECT id, 'ON_CALL' FROM runbook_document
WHERE stable_key IN ('kafka-consumer-lag', 'mysql-connection-saturation', 'kubernetes-crashloop');

INSERT INTO runbook_retrieval_eval_case (case_key, query_text, expected_stable_key) VALUES
    ('redis-client-queue', '缓存客户端连接排队 active pending 命令耗时升高', 'legacy-runbook-2'),
    ('redis-pool-exhaustion', '连接池借不到连接 max-active 等待队列', 'legacy-runbook-2'),
    ('auth-release-regression', '登录凭证格式兼容 错误码与发布批次回归', 'legacy-runbook-3'),
    ('api-latency-change-window', '服务端 P99 升高 下游依赖与最近变更窗口', 'legacy-runbook-1'),
    ('kafka-backlog', '订单 topic backlog 消费者处理不过来 offset commit', 'kafka-consumer-lag'),
    ('kafka-rebalance', '消息积压 partition rebalance consumer group 抖动', 'kafka-consumer-lag'),
    ('mysql-pool-wait', '数据库连接池 pending too many connections 慢事务', 'mysql-connection-saturation'),
    ('mysql-connection-leak', 'active connections 打满 疑似连接泄漏 wait timeout', 'mysql-connection-saturation'),
    ('k8s-restart-loop', 'Pod 反复重启 previous logs 退出码', 'kubernetes-crashloop'),
    ('k8s-oom-probe', 'CrashLoopBackOff OOMKilled liveness probe 镜像回滚', 'kubernetes-crashloop');
