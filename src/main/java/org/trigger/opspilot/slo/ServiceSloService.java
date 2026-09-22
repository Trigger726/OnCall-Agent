package org.trigger.opspilot.slo;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.trigger.opspilot.audit.AuditService;
import org.trigger.opspilot.common.ApiException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

@Service
public class ServiceSloService {
    private final JdbcClient jdbcClient;
    private final PrometheusSloClient prometheus;
    private final AuditService auditService;

    public ServiceSloService(JdbcClient jdbcClient, PrometheusSloClient prometheus,
                             AuditService auditService) {
        this.jdbcClient = jdbcClient;
        this.prometheus = prometheus;
        this.auditService = auditService;
    }

    public SloOverview overview(Instant at) {
        Instant evaluatedAt = at == null ? Instant.now() : at;
        List<ObjectiveRow> objectives = jdbcClient.sql("""
                        SELECT slo.id, slo.service_resource_id, resource.resource_code,
                               resource.name AS service_name, slo.name AS objective_name,
                               slo.target_percent, slo.window_days,
                               slo.good_events_query_template, slo.total_events_query_template,
                               slo.version, slo.updated_at
                        FROM service_slo_objective slo
                        JOIN cmdb_resource resource ON resource.id = slo.service_resource_id
                        WHERE slo.enabled = TRUE
                        ORDER BY resource.resource_code
                        """)
                .query((rs, rowNum) -> new ObjectiveRow(
                        rs.getLong("id"), rs.getLong("service_resource_id"),
                        rs.getString("resource_code"), rs.getString("service_name"),
                        rs.getString("objective_name"),
                        rs.getDouble("target_percent"), rs.getInt("window_days"),
                        rs.getString("good_events_query_template"),
                        rs.getString("total_events_query_template"), rs.getInt("version"),
                        rs.getObject("updated_at", LocalDateTime.class))).list();
        return new SloOverview(evaluatedAt, prometheus.available(), objectives.stream()
                .map(row -> evaluate(row, evaluatedAt)).toList());
    }

    @Transactional
    public ObjectiveView update(long id, UpdateCommand command, long actorId) {
        String name = requireText(command.name(), "目标名称", 120);
        if (!Double.isFinite(command.targetPercent())
                || command.targetPercent() <= 0 || command.targetPercent() >= 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SLO_INVALID_TARGET", "SLO 目标必须大于 0 且小于 100");
        }
        if (command.windowDays() < 1 || command.windowDays() > 90) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SLO_INVALID_WINDOW", "滚动窗口必须为 1 至 90 天");
        }
        String goodQuery = validateQuery(command.goodEventsQueryTemplate(), "好事件 PromQL");
        String totalQuery = validateQuery(command.totalEventsQueryTemplate(), "总事件 PromQL");
        int updated = jdbcClient.sql("""
                        UPDATE service_slo_objective
                        SET name = :name, target_percent = :target, window_days = :windowDays,
                            good_events_query_template = :goodQuery,
                            total_events_query_template = :totalQuery,
                            updated_by = :actorId, version = version + 1, updated_at = CURRENT_TIMESTAMP
                        WHERE id = :id AND version = :version
                        """).param("name", name).param("target", command.targetPercent())
                .param("windowDays", command.windowDays()).param("goodQuery", goodQuery)
                .param("totalQuery", totalQuery).param("actorId", actorId)
                .param("id", id).param("version", command.expectedVersion()).update();
        if (updated == 0) {
            if (!exists(id)) throw new ApiException(HttpStatus.NOT_FOUND, "SLO_NOT_FOUND", "SLO 目标不存在");
            throw new ApiException(HttpStatus.CONFLICT, "SLO_VERSION_CONFLICT", "SLO 目标已被其他人更新，请刷新后重试");
        }
        auditService.record("SLO_OBJECTIVE_UPDATE", "SERVICE_SLO_OBJECTIVE", id,
                "目标 " + command.targetPercent() + "%；窗口 " + command.windowDays()
                        + " 天；版本 " + command.expectedVersion() + " -> " + (command.expectedVersion() + 1));
        return viewById(id);
    }

    private ObjectiveView evaluate(ObjectiveRow row, Instant at) {
        ObjectiveView measured = row.toView().withMeasurement(evaluateMeasurement(row, at));
        return measured.withBurnRate(evaluateBurnRate(row, at));
    }

    private Measurement evaluateMeasurement(ObjectiveRow row, Instant at) {
        if (!prometheus.available()) {
            return new Measurement("PROVIDER_DISABLED", null, null, null,
                    null, null, null, null, null, at, null,
                    "Prometheus 未启用；未使用本地演示数据替代");
        }
        String goodQuery = render(row.goodQuery(), row.serviceCode(), row.windowDays());
        String totalQuery = render(row.totalQuery(), row.serviceCode(), row.windowDays());
        try {
            OptionalDouble goodResult = prometheus.query(goodQuery, at);
            OptionalDouble totalResult = prometheus.query(totalQuery, at);
            if (goodResult.isEmpty() || totalResult.isEmpty() || totalResult.getAsDouble() <= 0) {
                return new Measurement("NO_DATA", null, null, null,
                        null, null, null, null, null, at, prometheus.externalRef(),
                        "查询没有返回有效的正分母");
            }
            double good = goodResult.getAsDouble();
            double total = totalResult.getAsDouble();
            if (good < 0 || good > total) {
                return new Measurement("INVALID_DATA", round(good), round(total), null,
                        null, null, null, null, null, at, prometheus.externalRef(),
                        "好事件必须处于 0 到总事件之间");
            }
            double bad = total - good;
            double sli = good * 100 / total;
            double allowedBad = total * (100 - row.targetPercent()) / 100;
            double remaining = allowedBad - bad;
            double consumedPercent = bad * 100 / allowedBad;
            return new Measurement(
                    sli >= row.targetPercent() ? "MET" : "BREACHED",
                    round(good), round(total), round(bad), round(sli), round(allowedBad),
                    round(bad), round(remaining), round(consumedPercent), at,
                    prometheus.externalRef(), null);
        } catch (RuntimeException exception) {
            return new Measurement("PROVIDER_ERROR", null, null, null,
                    null, null, null, null, null, at, prometheus.externalRef(),
                    "Prometheus 查询失败（" + exception.getClass().getSimpleName() + "）");
        }
    }

    private SloBurnRateCalculator.Assessment evaluateBurnRate(ObjectiveRow row, Instant at) {
        if (!prometheus.available()) {
            return SloBurnRateCalculator.unavailable("PROVIDER_DISABLED",
                    "Prometheus 未启用；不生成虚假燃烧率");
        }
        Map<String, SloBurnRateCalculator.WindowSample> samples = new LinkedHashMap<>();
        for (String window : SloBurnRateCalculator.requiredWindows()) {
            samples.put(window, queryWindow(row, window, at));
        }
        return SloBurnRateCalculator.assess(row.targetPercent(), samples);
    }

    private SloBurnRateCalculator.WindowSample queryWindow(ObjectiveRow row, String window, Instant at) {
        try {
            OptionalDouble goodResult = prometheus.query(render(row.goodQuery(), row.serviceCode(), window), at);
            OptionalDouble totalResult = prometheus.query(render(row.totalQuery(), row.serviceCode(), window), at);
            if (goodResult.isEmpty() || totalResult.isEmpty() || totalResult.getAsDouble() <= 0) {
                return new SloBurnRateCalculator.WindowSample("NO_DATA", null, null,
                        window + " 窗口没有有效正分母");
            }
            double good = goodResult.getAsDouble();
            double total = totalResult.getAsDouble();
            if (good < 0 || good > total) {
                return new SloBurnRateCalculator.WindowSample("INVALID_DATA", round(good), round(total),
                        window + " 窗口的好事件不在 0 到总事件之间");
            }
            return new SloBurnRateCalculator.WindowSample("VALID", round(good), round(total), null);
        } catch (RuntimeException exception) {
            return new SloBurnRateCalculator.WindowSample("PROVIDER_ERROR", null, null,
                    window + " 窗口查询失败（" + exception.getClass().getSimpleName() + "）");
        }
    }

    private ObjectiveView viewById(long id) {
        return jdbcClient.sql("""
                        SELECT slo.id, slo.service_resource_id, resource.resource_code,
                               resource.name AS service_name, slo.name AS objective_name,
                               slo.target_percent, slo.window_days,
                               slo.good_events_query_template, slo.total_events_query_template,
                               slo.version, slo.updated_at
                        FROM service_slo_objective slo
                        JOIN cmdb_resource resource ON resource.id = slo.service_resource_id
                        WHERE slo.id = :id
                        """).param("id", id).query((rs, rowNum) -> new ObjectiveView(
                        rs.getLong("id"), rs.getLong("service_resource_id"),
                        rs.getString("resource_code"), rs.getString("service_name"),
                        rs.getString("objective_name"),
                        rs.getDouble("target_percent"), rs.getInt("window_days"),
                        rs.getString("good_events_query_template"),
                        rs.getString("total_events_query_template"), rs.getInt("version"),
                        rs.getObject("updated_at", LocalDateTime.class), null, null)).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SLO_NOT_FOUND", "SLO 目标不存在"));
    }

    private boolean exists(long id) {
        return jdbcClient.sql("SELECT COUNT(*) FROM service_slo_objective WHERE id = :id")
                .param("id", id).query(Long.class).single() > 0;
    }

    static String render(String template, String serviceCode, int windowDays) {
        return render(template, serviceCode, windowDays + "d");
    }

    static String render(String template, String serviceCode, String window) {
        String safeService = serviceCode.replace("\\", "\\\\").replace("\"", "\\\"");
        return template.replace("{{service}}", safeService).replace("{{window}}", window);
    }

    private static String validateQuery(String value, String field) {
        String query = requireText(value, field, 1000);
        if (!query.contains("{{window}}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SLO_QUERY_WINDOW_REQUIRED",
                    field + " 必须包含 {{window}} 占位符");
        }
        return query;
    }

    private static String requireText(String value, String field, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SLO_INVALID_FIELD",
                    field + "不能为空且长度不能超过 " + maxLength);
        }
        return normalized;
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private record ObjectiveRow(long id, long serviceResourceId, String serviceCode, String serviceName,
                                String name, double targetPercent, int windowDays, String goodQuery,
                                String totalQuery, int version, LocalDateTime updatedAt) {
        ObjectiveView toView() {
            return new ObjectiveView(id, serviceResourceId, serviceCode, serviceName, name, targetPercent,
                    windowDays, goodQuery, totalQuery, version, updatedAt, null, null);
        }
    }

    public record SloOverview(Instant evaluatedAt, boolean prometheusEnabled, List<ObjectiveView> objectives) {
    }

    public record ObjectiveView(long id, long serviceResourceId, String serviceCode, String serviceName,
                                String name, double targetPercent, int windowDays,
                                String goodEventsQueryTemplate, String totalEventsQueryTemplate,
                                int version, LocalDateTime updatedAt, Measurement measurement,
                                SloBurnRateCalculator.Assessment burnRate) {
        ObjectiveView withMeasurement(Measurement value) {
            return new ObjectiveView(id, serviceResourceId, serviceCode, serviceName, name, targetPercent,
                    windowDays, goodEventsQueryTemplate, totalEventsQueryTemplate, version, updatedAt,
                    value, burnRate);
        }

        ObjectiveView withBurnRate(SloBurnRateCalculator.Assessment value) {
            return new ObjectiveView(id, serviceResourceId, serviceCode, serviceName, name, targetPercent,
                    windowDays, goodEventsQueryTemplate, totalEventsQueryTemplate, version, updatedAt,
                    measurement, value);
        }
    }

    public record Measurement(String status, Double goodEvents, Double totalEvents, Double badEvents,
                              Double sliPercent, Double errorBudgetEvents, Double consumedEvents,
                              Double remainingEvents, Double consumedPercent, Instant evaluatedAt,
                              String externalRef, String message) {
    }

    public record UpdateCommand(int expectedVersion, String name, double targetPercent, int windowDays,
                                String goodEventsQueryTemplate, String totalEventsQueryTemplate) {
    }
}
