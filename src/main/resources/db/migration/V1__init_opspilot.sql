CREATE TABLE sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    role_code VARCHAR(32) NOT NULL,
    department VARCHAR(128),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE cmdb_resource (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    resource_code VARCHAR(64) NOT NULL UNIQUE,
    resource_type VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    environment VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    owner_user_id BIGINT,
    description VARCHAR(500),
    attributes_json TEXT,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_resource_owner FOREIGN KEY (owner_user_id) REFERENCES sys_user(id)
);

CREATE TABLE cmdb_relation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_resource_id BIGINT NOT NULL,
    target_resource_id BIGINT NOT NULL,
    relation_type VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_resource_relation UNIQUE (source_resource_id, target_resource_id, relation_type),
    CONSTRAINT fk_relation_source FOREIGN KEY (source_resource_id) REFERENCES cmdb_resource(id),
    CONSTRAINT fk_relation_target FOREIGN KEY (target_resource_id) REFERENCES cmdb_resource(id)
);

CREATE TABLE change_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    change_code VARCHAR(64) NOT NULL UNIQUE,
    resource_id BIGINT NOT NULL,
    change_type VARCHAR(32) NOT NULL,
    summary VARCHAR(300) NOT NULL,
    operator_id BIGINT,
    status VARCHAR(24) NOT NULL,
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_change_resource FOREIGN KEY (resource_id) REFERENCES cmdb_resource(id),
    CONSTRAINT fk_change_operator FOREIGN KEY (operator_id) REFERENCES sys_user(id)
);

CREATE TABLE incident (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    incident_code VARCHAR(64) NOT NULL UNIQUE,
    title VARCHAR(240) NOT NULL,
    description TEXT,
    severity VARCHAR(8) NOT NULL,
    status VARCHAR(24) NOT NULL,
    service_resource_id BIGINT NOT NULL,
    commander_id BIGINT,
    assignee_id BIGINT,
    acknowledged_at TIMESTAMP,
    resolved_at TIMESTAMP,
    closed_at TIMESTAMP,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_incident_resource FOREIGN KEY (service_resource_id) REFERENCES cmdb_resource(id),
    CONSTRAINT fk_incident_commander FOREIGN KEY (commander_id) REFERENCES sys_user(id),
    CONSTRAINT fk_incident_assignee FOREIGN KEY (assignee_id) REFERENCES sys_user(id)
);

CREATE TABLE alert_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source VARCHAR(64) NOT NULL,
    external_event_id VARCHAR(128),
    fingerprint VARCHAR(128) NOT NULL,
    service_resource_id BIGINT NOT NULL,
    severity VARCHAR(8) NOT NULL,
    status VARCHAR(24) NOT NULL,
    title VARCHAR(240) NOT NULL,
    description TEXT,
    labels_json TEXT,
    first_occurred_at TIMESTAMP NOT NULL,
    last_occurred_at TIMESTAMP NOT NULL,
    occurrence_count INT NOT NULL DEFAULT 1,
    incident_id BIGINT,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_alert_external UNIQUE (source, external_event_id),
    CONSTRAINT fk_alert_resource FOREIGN KEY (service_resource_id) REFERENCES cmdb_resource(id),
    CONSTRAINT fk_alert_incident FOREIGN KEY (incident_id) REFERENCES incident(id)
);

CREATE INDEX idx_alert_fingerprint ON alert_event(fingerprint, status, last_occurred_at);
CREATE INDEX idx_incident_status ON incident(status, severity, updated_at);

CREATE TABLE incident_timeline (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    incident_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    from_status VARCHAR(24),
    to_status VARCHAR(24),
    actor_id BIGINT,
    content VARCHAR(1000) NOT NULL,
    evidence_ref VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_timeline_incident FOREIGN KEY (incident_id) REFERENCES incident(id),
    CONSTRAINT fk_timeline_actor FOREIGN KEY (actor_id) REFERENCES sys_user(id)
);

CREATE TABLE oncall_schedule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    service_resource_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_schedule_resource FOREIGN KEY (service_resource_id) REFERENCES cmdb_resource(id)
);

CREATE TABLE oncall_shift (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    schedule_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    starts_at TIMESTAMP NOT NULL,
    ends_at TIMESTAMP NOT NULL,
    override_flag BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_shift_schedule FOREIGN KEY (schedule_id) REFERENCES oncall_schedule(id),
    CONSTRAINT fk_shift_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
);

CREATE TABLE escalation_policy (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    service_resource_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_policy_resource FOREIGN KEY (service_resource_id) REFERENCES cmdb_resource(id)
);

CREATE TABLE escalation_step (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    policy_id BIGINT NOT NULL,
    step_order INT NOT NULL,
    delay_minutes INT NOT NULL,
    target_type VARCHAR(24) NOT NULL,
    target_ref VARCHAR(64) NOT NULL,
    CONSTRAINT uq_escalation_step UNIQUE (policy_id, step_order),
    CONSTRAINT fk_step_policy FOREIGN KEY (policy_id) REFERENCES escalation_policy(id)
);

CREATE TABLE notification_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    incident_id BIGINT NOT NULL,
    channel VARCHAR(24) NOT NULL,
    recipient VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL,
    message VARCHAR(500) NOT NULL,
    sent_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_notification_incident FOREIGN KEY (incident_id) REFERENCES incident(id)
);

CREATE TABLE runbook (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    resource_type VARCHAR(32),
    symptom_keyword VARCHAR(128) NOT NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE investigation_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    incident_id BIGINT NOT NULL,
    engine VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    summary TEXT NOT NULL,
    hypothesis TEXT NOT NULL,
    confidence DECIMAL(5, 2) NOT NULL,
    suggestions TEXT NOT NULL,
    evidence_json TEXT NOT NULL,
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_report_incident FOREIGN KEY (incident_id) REFERENCES incident(id),
    CONSTRAINT fk_report_creator FOREIGN KEY (created_by) REFERENCES sys_user(id)
);

CREATE TABLE audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    actor_id BIGINT,
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(48) NOT NULL,
    target_id VARCHAR(64),
    detail VARCHAR(1000),
    ip_address VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_audit_actor FOREIGN KEY (actor_id) REFERENCES sys_user(id)
);

INSERT INTO sys_user (id, username, password_hash, display_name, role_code, department) VALUES
    (1, 'admin', '$2a$10$C4NFIuL4q4SWU6RiC2rK0ur.QRr1OpD.rT7lA7pDofEz19dtv6/ri', '系统管理员', 'ADMIN', '数字化运营中心'),
    (2, 'zhangwei', '$2a$10$C4NFIuL4q4SWU6RiC2rK0ur.QRr1OpD.rT7lA7pDofEz19dtv6/ri', '张伟', 'ON_CALL', '生产运行处'),
    (3, 'lina', '$2a$10$C4NFIuL4q4SWU6RiC2rK0ur.QRr1OpD.rT7lA7pDofEz19dtv6/ri', '李娜', 'OPS_MANAGER', '信息通信部'),
    (4, 'auditor', '$2a$10$C4NFIuL4q4SWU6RiC2rK0ur.QRr1OpD.rT7lA7pDofEz19dtv6/ri', '陈审计', 'AUDITOR', '审计风控部');

INSERT INTO cmdb_resource (id, resource_code, resource_type, name, environment, status, owner_user_id, description, attributes_json) VALUES
    (1, 'APP-SETTLEMENT', 'APPLICATION', '统一结算服务', 'PRODUCTION', 'DEGRADED', 2, '能源交易结算核心应用', '{"language":"Java","sla":"99.95%","region":"华东"}'),
    (2, 'APP-PORTAL', 'APPLICATION', '客户服务门户', 'PRODUCTION', 'RUNNING', 2, '面向企业客户的统一服务门户', '{"framework":"Vue","sla":"99.9%"}'),
    (3, 'APP-AUTH', 'APPLICATION', '统一身份认证', 'PRODUCTION', 'RUNNING', 3, '统一认证与权限中心', '{"protocol":"OIDC","instances":4}'),
    (4, 'MID-REDIS-01', 'MIDDLEWARE', '结算 Redis 集群', 'PRODUCTION', 'DEGRADED', 2, '结算缓存与分布式锁', '{"version":"7.2","nodes":6}'),
    (5, 'DB-SETTLEMENT-01', 'DATABASE', '结算 MySQL 主库', 'PRODUCTION', 'RUNNING', 3, '结算业务主数据库', '{"version":"8.0","ha":"MGR"}'),
    (6, 'API-PAYMENT', 'API', '资金支付接口', 'PRODUCTION', 'RUNNING', 3, '外部资金通道统一接口', '{"protocol":"HTTPS","timeoutMs":3000}');

INSERT INTO cmdb_relation (source_resource_id, target_resource_id, relation_type) VALUES
    (2, 1, 'CALLS'), (1, 3, 'CALLS'), (1, 4, 'DEPENDS_ON'), (1, 5, 'DEPENDS_ON'), (1, 6, 'CALLS');

INSERT INTO change_record (change_code, resource_id, change_type, summary, operator_id, status, started_at, finished_at) VALUES
    ('CHG-20260819-001', 4, 'CONFIG', 'Redis 连接池上限由 200 调整为 120', 2, 'COMPLETED', '2026-08-19 08:40:00', '2026-08-19 08:47:00'),
    ('CHG-20260818-003', 3, 'RELEASE', '认证服务发布 v2.8.1', 3, 'COMPLETED', '2026-08-18 21:00:00', '2026-08-18 21:25:00');

INSERT INTO incident (id, incident_code, title, description, severity, status, service_resource_id, commander_id, assignee_id, acknowledged_at, version, created_at, updated_at) VALUES
    (1, 'INC-20260819-0001', '统一结算接口持续超时', '结算高峰期接口 P95 超过 5 秒并出现缓存连接失败。', 'P1', 'INVESTIGATING', 1, 3, 2, '2026-08-19 09:05:00', 2, '2026-08-19 09:02:00', '2026-08-19 09:16:00'),
    (2, 'INC-20260818-0002', '统一认证登录错误率升高', '发布后少量旧客户端 token 刷新失败。', 'P2', 'RESOLVED', 3, 3, 3, '2026-08-18 21:34:00', 4, '2026-08-18 21:31:00', '2026-08-18 22:12:00');

INSERT INTO alert_event (source, external_event_id, fingerprint, service_resource_id, severity, status, title, description, labels_json, first_occurred_at, last_occurred_at, occurrence_count, incident_id) VALUES
    ('prometheus', 'pm-89012', 'seed-settlement-timeout', 1, 'P1', 'FIRING', '结算接口 P95 延迟超过 5 秒', '过去 10 分钟 P95=6.8s', '{"cluster":"prod-east","metric":"http_p95"}', '2026-08-19 09:01:00', '2026-08-19 09:18:00', 18, 1),
    ('prometheus', 'pm-89013', 'seed-redis-pool', 4, 'P1', 'FIRING', 'Redis 连接池等待线程过高', 'active=120, pending=84', '{"cluster":"prod-east","metric":"lettuce_pool_pending"}', '2026-08-19 09:03:00', '2026-08-19 09:18:00', 16, 1),
    ('apm', 'apm-44108', 'seed-auth-error', 3, 'P2', 'RESOLVED', '认证 token 刷新错误率超过 3%', '旧版客户端 refresh_token 格式不兼容', '{"release":"v2.8.1"}', '2026-08-18 21:29:00', '2026-08-18 22:08:00', 9, 2);

INSERT INTO incident_timeline (incident_id, event_type, from_status, to_status, actor_id, content, evidence_ref, created_at) VALUES
    (1, 'CREATED', NULL, 'OPEN', NULL, '系统根据 2 条关联告警自动创建 Incident。', 'alert:1,2', '2026-08-19 09:02:00'),
    (1, 'STATUS_CHANGED', 'OPEN', 'ACKNOWLEDGED', 2, '值班工程师已确认影响范围。', NULL, '2026-08-19 09:05:00'),
    (1, 'STATUS_CHANGED', 'ACKNOWLEDGED', 'INVESTIGATING', 2, '开始检查应用、缓存与最近变更。', NULL, '2026-08-19 09:08:00'),
    (1, 'NOTE', NULL, NULL, 2, '发现 Redis pending 线程与 08:40 连接池变更时间高度相关。', 'change:1', '2026-08-19 09:16:00'),
    (2, 'CREATED', NULL, 'OPEN', NULL, '系统根据认证错误率告警自动创建 Incident。', 'alert:3', '2026-08-18 21:31:00'),
    (2, 'STATUS_CHANGED', 'INVESTIGATING', 'MITIGATED', 3, '已开启旧 token 兼容开关。', 'change:2', '2026-08-18 21:56:00'),
    (2, 'STATUS_CHANGED', 'MITIGATED', 'RESOLVED', 3, '错误率恢复到基线并持续观察 15 分钟。', NULL, '2026-08-18 22:12:00');

INSERT INTO oncall_schedule (id, service_resource_id, name, timezone) VALUES
    (1, 1, '结算系统 7x24 值班', 'Asia/Shanghai'),
    (2, 3, '基础平台值班', 'Asia/Shanghai');

INSERT INTO oncall_shift (schedule_id, user_id, starts_at, ends_at) VALUES
    (1, 2, '2026-08-19 08:00:00', '2026-08-20 08:00:00'),
    (2, 3, '2026-08-19 08:00:00', '2026-08-20 08:00:00');

INSERT INTO escalation_policy (id, service_resource_id, name) VALUES
    (1, 1, '结算系统 P1 升级策略');

INSERT INTO escalation_step (policy_id, step_order, delay_minutes, target_type, target_ref) VALUES
    (1, 1, 0, 'ON_CALL', 'schedule:1'),
    (1, 2, 10, 'USER', '3'),
    (1, 3, 20, 'ROLE', 'ADMIN');

INSERT INTO runbook (resource_type, symptom_keyword, title, content) VALUES
    ('APPLICATION', '超时', '应用接口超时排查手册', '1. 确认入口流量与 P95/P99；2. 检查下游依赖；3. 对照 2 小时内变更；4. 先限流或回滚再继续定位。'),
    ('MIDDLEWARE', 'Redis', 'Redis 连接池耗尽处置', '检查 active、idle、pending；核对连接池上限变更；确认慢命令；必要时回滚配置并分批重启客户端实例。'),
    ('APPLICATION', '认证', '认证错误率升高排查', '按客户端版本、错误码和发布批次聚合；核对 token 格式兼容；灰度开启兼容策略并观察错误率。');

INSERT INTO investigation_report (incident_id, engine, status, summary, hypothesis, confidence, suggestions, evidence_json, created_by, created_at) VALUES
    (1, 'EVIDENCE_RULES', 'COMPLETED', '结算超时与 Redis 连接池排队同时出现，且紧邻连接池上限调整。', '连接池容量下调导致高峰期连接等待，进而放大结算接口延迟。', 0.88, '优先回滚连接池上限；观察 pending 与接口 P95；确认恢复后补充容量基线和变更校验。', '[{"type":"alert","ref":"alert:2","text":"Redis pending=84"},{"type":"change","ref":"change:1","text":"连接池上限 200 调整为 120"}]', 2, '2026-08-19 09:20:00');

INSERT INTO audit_log (actor_id, action, target_type, target_id, detail, ip_address, created_at) VALUES
    (2, 'INCIDENT_ACKNOWLEDGE', 'INCIDENT', '1', '确认 P1 Incident 并接手处置', '10.20.8.15', '2026-08-19 09:05:00'),
    (3, 'INCIDENT_ASSIGN', 'INCIDENT', '1', '将处置人指派给张伟', '10.20.8.21', '2026-08-19 09:06:00');
