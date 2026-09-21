CREATE TABLE service_slo_objective (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    service_resource_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    target_percent DECIMAL(6, 3) NOT NULL,
    window_days INT NOT NULL,
    good_events_query_template VARCHAR(1000) NOT NULL,
    total_events_query_template VARCHAR(1000) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version INT NOT NULL DEFAULT 0,
    updated_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_service_slo_objective UNIQUE (service_resource_id),
    CONSTRAINT ck_service_slo_target CHECK (target_percent > 0 AND target_percent < 100),
    CONSTRAINT ck_service_slo_window CHECK (window_days BETWEEN 1 AND 90),
    CONSTRAINT fk_service_slo_resource FOREIGN KEY (service_resource_id) REFERENCES cmdb_resource(id),
    CONSTRAINT fk_service_slo_updater FOREIGN KEY (updated_by) REFERENCES sys_user(id)
);

INSERT INTO service_slo_objective(
    service_resource_id, name, target_percent, window_days,
    good_events_query_template, total_events_query_template)
SELECT id, '成功请求比例', 99.950, 30,
       'sum(increase(opspilot_http_requests_good_total{service_code="{{service}}"}[{{window}}]))',
       'sum(increase(opspilot_http_requests_total{service_code="{{service}}"}[{{window}}]))'
FROM cmdb_resource WHERE resource_code = 'APP-SETTLEMENT';

INSERT INTO service_slo_objective(
    service_resource_id, name, target_percent, window_days,
    good_events_query_template, total_events_query_template)
SELECT id, '成功请求比例', 99.900, 30,
       'sum(increase(opspilot_http_requests_good_total{service_code="{{service}}"}[{{window}}]))',
       'sum(increase(opspilot_http_requests_total{service_code="{{service}}"}[{{window}}]))'
FROM cmdb_resource WHERE resource_code = 'APP-AUTH';

CREATE INDEX idx_service_slo_enabled ON service_slo_objective(enabled, service_resource_id);
