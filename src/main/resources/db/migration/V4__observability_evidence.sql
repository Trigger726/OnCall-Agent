CREATE TABLE observability_metric_sample (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    resource_id BIGINT NOT NULL,
    metric_name VARCHAR(160) NOT NULL,
    metric_value DECIMAL(20, 6) NOT NULL,
    unit VARCHAR(32) NOT NULL,
    labels_json TEXT NOT NULL,
    observed_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_metric_sample_resource FOREIGN KEY (resource_id) REFERENCES cmdb_resource(id)
);

CREATE INDEX idx_metric_sample_resource_time
    ON observability_metric_sample(resource_id, observed_at);

CREATE TABLE observability_log_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    resource_id BIGINT NOT NULL,
    level VARCHAR(16) NOT NULL,
    logger_name VARCHAR(160) NOT NULL,
    message TEXT NOT NULL,
    trace_id VARCHAR(128),
    metadata_json TEXT NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_log_event_resource FOREIGN KEY (resource_id) REFERENCES cmdb_resource(id)
);

CREATE INDEX idx_log_event_resource_time
    ON observability_log_event(resource_id, occurred_at);

INSERT INTO observability_metric_sample(resource_id, metric_name, metric_value, unit, labels_json, observed_at) VALUES
    (1, 'http_request_duration_p95', 6.800000, 's', '{"route":"/api/settlements","cluster":"prod-east"}', '2026-08-19 09:18:00'),
    (1, 'http_server_error_rate', 0.073000, 'ratio', '{"route":"/api/settlements","status":"5xx"}', '2026-08-19 09:18:00'),
    (4, 'redis_pool_active', 120.000000, 'connections', '{"cluster":"settlement-redis","max":"120"}', '2026-08-19 09:18:00'),
    (4, 'redis_pool_pending', 84.000000, 'threads', '{"cluster":"settlement-redis"}', '2026-08-19 09:18:00'),
    (4, 'redis_command_duration_p95', 3.240000, 's', '{"command":"GET","cluster":"settlement-redis"}', '2026-08-19 09:17:00'),
    (3, 'auth_refresh_error_rate', 0.034000, 'ratio', '{"release":"v2.8.1","client":"legacy"}', '2026-08-18 21:42:00'),
    (3, 'auth_refresh_error_rate', 0.004000, 'ratio', '{"release":"v2.8.1","client":"legacy"}', '2026-08-18 22:08:00');

INSERT INTO observability_log_event(resource_id, level, logger_name, message, trace_id, metadata_json, occurred_at) VALUES
    (1, 'ERROR', 'SettlementController', '结算请求失败，下游 Redis 命令超时；token=fake-demo-token-123', 'settle-8af03', '{"route":"/api/settlements","status":"500"}', '2026-08-19 09:17:42'),
    (1, 'WARN', 'RedisCheckoutRepository', '等待 Redis 连接超过 3000ms，pending=84', 'settle-8af03', '{"timeoutMs":"3000","pending":"84"}', '2026-08-19 09:17:41'),
    (4, 'WARN', 'LettucePoolMonitor', '连接池已达到上限，active=120 max=120 pending=84', 'settle-8af03', '{"active":"120","max":"120","pending":"84"}', '2026-08-19 09:17:40'),
    (4, 'INFO', 'RedisClusterClient', '节点 redis-settlement-02 健康检查成功，clientIp=10.20.8.15', NULL, '{"node":"redis-settlement-02"}', '2026-08-19 09:16:55'),
    (3, 'ERROR', 'RefreshTokenService', '旧客户端 refresh_token 格式校验失败，authorization=Bearer fake-auth-token', 'auth-44c10', '{"release":"v2.8.1","clientVersion":"1.4"}', '2026-08-18 21:40:12'),
    (3, 'INFO', 'RefreshTokenService', '兼容策略生效，刷新错误率恢复到基线', 'auth-44c11', '{"release":"v2.8.1","strategy":"legacy-compatible"}', '2026-08-18 22:07:35');
