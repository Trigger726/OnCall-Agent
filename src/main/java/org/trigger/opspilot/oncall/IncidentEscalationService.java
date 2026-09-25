package org.trigger.opspilot.oncall;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.trigger.opspilot.audit.AuditService;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
public class IncidentEscalationService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcClient jdbcClient;
    private final AuditService auditService;

    public IncidentEscalationService(JdbcClient jdbcClient, AuditService auditService) {
        this.jdbcClient = jdbcClient;
        this.auditService = auditService;
    }

    @Transactional
    public ScanResult scan(LocalDateTime requestedAt, Long actorId, String sourceIp) {
        LocalDateTime asOf = requestedAt == null ? LocalDateTime.now(BUSINESS_ZONE) : requestedAt;
        List<Long> candidates = jdbcClient.sql("""
                        SELECT DISTINCT incident.id FROM incident
                        JOIN escalation_policy policy ON policy.service_resource_id = incident.service_resource_id
                          AND policy.active = TRUE AND (policy.severity IS NULL OR policy.severity = incident.severity)
                        JOIN escalation_step step ON step.policy_id = policy.id
                        LEFT JOIN incident_escalation_event event
                          ON event.incident_id = incident.id AND event.step_id = step.id
                        WHERE incident.status = 'OPEN' AND event.id IS NULL
                          AND TIMESTAMPADD(MINUTE, step.delay_minutes, incident.created_at) <= :asOf
                        ORDER BY incident.id LIMIT 100
                        """).param("asOf", asOf).query(Long.class).list();
        int routed = 0;
        int noTarget = 0;
        for (Long incidentId : candidates) {
            StepCounts counts = executeDueSteps(incidentId, asOf, actorId, sourceIp);
            routed += counts.routed();
            noTarget += counts.noTarget();
        }
        return new ScanResult(asOf, candidates.size(), routed, noTarget);
    }

    @Transactional
    public void routeNewIncident(long incidentId) {
        executeDueSteps(incidentId, LocalDateTime.now(BUSINESS_ZONE), null, "alert-intake");
    }

    private StepCounts executeDueSteps(long incidentId, LocalDateTime asOf,
                                       Long actorId, String sourceIp) {
        Incident incident = jdbcClient.sql("""
                        SELECT id, incident_code, status, service_resource_id, severity, created_at
                        FROM incident WHERE id = :id FOR UPDATE
                        """).param("id", incidentId).query((rs, rowNum) -> new Incident(
                        rs.getLong("id"), rs.getString("incident_code"), rs.getString("status"),
                        rs.getLong("service_resource_id"), rs.getString("severity"),
                        rs.getObject("created_at", LocalDateTime.class))).single();
        if (!"OPEN".equals(incident.status())) return new StepCounts(0, 0);
        List<Step> steps = jdbcClient.sql("""
                        SELECT policy.id AS policy_id, step.id AS step_id, step.step_order,
                               step.delay_minutes, step.target_type, step.target_ref
                        FROM escalation_policy policy
                        JOIN escalation_step step ON step.policy_id = policy.id
                        WHERE policy.service_resource_id = :serviceId AND policy.active = TRUE
                          AND (policy.severity IS NULL OR policy.severity = :severity)
                        ORDER BY policy.id, step.step_order
                        """).param("serviceId", incident.serviceId()).param("severity", incident.severity())
                .query((rs, rowNum) -> new Step(rs.getLong("policy_id"), rs.getLong("step_id"),
                        rs.getInt("step_order"), rs.getInt("delay_minutes"),
                        rs.getString("target_type"), rs.getString("target_ref"))).list();
        int routed = 0;
        int noTarget = 0;
        for (Step step : steps) {
            LocalDateTime dueAt = incident.createdAt().plusMinutes(step.delayMinutes());
            if (dueAt.isAfter(asOf) || alreadyExecuted(incident.id(), step.id())) continue;
            Route route = route(step, incident.serviceId(), asOf);
            String status = route.usernames().isEmpty() ? "NO_TARGET" : "ROUTED";
            jdbcClient.sql("""
                            INSERT INTO incident_escalation_event(
                              incident_id, policy_id, step_id, due_at, status, recipient, detail, executed_at)
                            VALUES (:incidentId, :policyId, :stepId, :dueAt, :status, :recipient, :detail, :asOf)
                            """).param("incidentId", incident.id()).param("policyId", step.policyId())
                    .param("stepId", step.id()).param("dueAt", dueAt)
                    .param("status", status).param("recipient", route.recipient())
                    .param("detail", route.detail()).param("asOf", asOf).update();
            for (String username : route.usernames()) {
                jdbcClient.sql("""
                                INSERT INTO notification_log(incident_id, channel, recipient, status, message, sent_at)
                                VALUES (:incidentId, 'IN_APP', :recipient, 'RECORDED', :message, :asOf)
                                """).param("incidentId", incident.id()).param("recipient", username)
                        .param("asOf", asOf)
                        .param("message", incident.code() + " 升级策略 STEP " + step.order()
                                + " 已到期，请确认 Incident").update();
            }
            String detail = "升级 STEP " + step.order() + "：" + step.targetType() + " "
                    + step.targetRef() + "；" + route.detail();
            jdbcClient.sql("""
                            INSERT INTO incident_timeline(incident_id, event_type, content, evidence_ref, created_at)
                            VALUES (:incidentId, :eventType, :content, :evidenceRef, :asOf)
                            """).param("incidentId", incident.id())
                    .param("eventType", route.usernames().isEmpty()
                            ? "ESCALATION_NO_TARGET" : "ESCALATION_ROUTED")
                    .param("content", detail).param("evidenceRef", "escalation-step:" + step.id())
                    .param("asOf", asOf).update();
            auditService.recordAs(actorId, sourceIp, "INCIDENT_ESCALATION_" + status,
                    "INCIDENT", incident.id(), detail);
            if (route.usernames().isEmpty()) noTarget++;
            else routed++;
        }
        return new StepCounts(routed, noTarget);
    }

    public List<EventView> recent() {
        return jdbcClient.sql("""
                        SELECT event.id, incident.id AS incident_id, incident.incident_code, incident.severity,
                               policy.name AS policy_name,
                               step.step_order, step.delay_minutes, step.target_type, step.target_ref,
                               event.due_at, event.status, event.recipient, event.detail, event.executed_at
                        FROM incident_escalation_event event
                        JOIN incident ON incident.id = event.incident_id
                        JOIN escalation_policy policy ON policy.id = event.policy_id
                        JOIN escalation_step step ON step.id = event.step_id
                        ORDER BY event.executed_at DESC, event.id DESC LIMIT 50
                        """).query((rs, rowNum) -> new EventView(rs.getLong("id"),
                        rs.getLong("incident_id"),
                        rs.getString("incident_code"), rs.getString("severity"),
                        rs.getString("policy_name"), rs.getInt("step_order"),
                        rs.getInt("delay_minutes"), rs.getString("target_type"),
                        rs.getString("target_ref"), rs.getObject("due_at", LocalDateTime.class),
                        rs.getString("status"), rs.getString("recipient"), rs.getString("detail"),
                        rs.getObject("executed_at", LocalDateTime.class))).list();
    }

    private boolean alreadyExecuted(long incidentId, long stepId) {
        return jdbcClient.sql("""
                        SELECT id FROM incident_escalation_event
                        WHERE incident_id = :incidentId AND step_id = :stepId FOR UPDATE
                        """).param("incidentId", incidentId).param("stepId", stepId)
                .query(Long.class).optional().isPresent();
    }

    private Route route(Step step, long serviceId, LocalDateTime at) {
        if ("ON_CALL".equals(step.targetType())) {
            if (!step.targetRef().startsWith("schedule:")) return Route.noTarget("班次引用无效");
            try {
                long scheduleId = Long.parseLong(step.targetRef().substring("schedule:".length()));
                return jdbcClient.sql("""
                                SELECT u.username FROM oncall_shift shift
                                JOIN oncall_schedule schedule ON schedule.id = shift.schedule_id
                                JOIN sys_user u ON u.id = shift.user_id
                                WHERE schedule.id = :scheduleId AND schedule.service_resource_id = :serviceId
                                  AND schedule.active = TRUE AND u.status = 'ACTIVE'
                                  AND shift.starts_at <= :at AND shift.ends_at > :at
                                ORDER BY shift.override_flag DESC, shift.starts_at DESC, shift.id DESC LIMIT 1
                                """).param("scheduleId", scheduleId).param("serviceId", serviceId)
                        .param("at", at).query(String.class).optional()
                        .map(username -> new Route(username, List.of(username), "已记录站内路由"))
                        .orElseGet(() -> Route.noTarget("没有生效的活跃值班人"));
            } catch (NumberFormatException exception) {
                return Route.noTarget("班次引用无效");
            }
        }
        if ("USER".equals(step.targetType())) {
            try {
                long userId = Long.parseLong(step.targetRef());
                return jdbcClient.sql("SELECT username FROM sys_user WHERE id = :id AND status = 'ACTIVE'")
                        .param("id", userId).query(String.class).optional()
                        .map(username -> new Route(username, List.of(username), "已记录站内路由"))
                        .orElseGet(() -> Route.noTarget("指定用户不存在或已停用"));
            } catch (NumberFormatException exception) {
                return Route.noTarget("用户引用无效");
            }
        }
        if ("ROLE".equals(step.targetType())) {
            List<String> usernames = jdbcClient.sql("""
                            SELECT username FROM sys_user WHERE role_code = :role AND status = 'ACTIVE' ORDER BY id
                            """).param("role", step.targetRef()).query(String.class).list();
            return usernames.isEmpty() ? Route.noTarget("角色没有活跃用户")
                    : new Route("role:" + step.targetRef(), usernames,
                            "已为 " + usernames.size() + " 名活跃用户记录站内路由");
        }
        return Route.noTarget("不支持的目标类型");
    }

    public record ScanResult(LocalDateTime asOf, int candidates, int routedSteps, int noTargetSteps) {
    }

    public record EventView(long id, long incidentId, String incidentCode, String severity, String policyName,
                            int step, int delayMinutes, String targetType, String targetRef,
                            LocalDateTime dueAt, String status, String recipient, String detail,
                            LocalDateTime executedAt) {
    }

    private record Incident(long id, String code, String status, long serviceId,
                            String severity, LocalDateTime createdAt) {
    }

    private record Step(long policyId, long id, int order, int delayMinutes,
                        String targetType, String targetRef) {
    }

    private record Route(String recipient, List<String> usernames, String detail) {
        static Route noTarget(String detail) {
            return new Route(null, List.of(), detail);
        }
    }

    private record StepCounts(int routed, int noTarget) {
    }
}
