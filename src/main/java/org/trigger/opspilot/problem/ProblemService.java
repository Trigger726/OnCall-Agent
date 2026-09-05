package org.trigger.opspilot.problem;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.trigger.opspilot.audit.AuditService;
import org.trigger.opspilot.common.ApiException;
import org.trigger.opspilot.common.PageResponse;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class ProblemService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> STATUSES = Set.of("OPEN", "KNOWN_ERROR", "RESOLVED");

    private final JdbcClient jdbcClient;
    private final AuditService auditService;

    public ProblemService(JdbcClient jdbcClient, AuditService auditService) {
        this.jdbcClient = jdbcClient;
        this.auditService = auditService;
    }

    public PageResponse<RecurrenceCandidate> recurrenceCandidates(
            LocalDate from, LocalDate to, Long serviceId, int page, int size) {
        Window window = window(from, to, 90);
        long serviceFilter = serviceId == null ? 0 : serviceId;
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(50, size));
        String where = """
                WHERE incident.created_at >= :start
                  AND incident.created_at < :endExclusive
                  AND (:serviceId = 0 OR incident.service_resource_id = :serviceId)
                  AND alert.incident_id IS NOT NULL
                """;
        long total = jdbcClient.sql("""
                        SELECT COUNT(*) FROM (
                          SELECT incident.service_resource_id, alert.fingerprint
                          FROM alert_event alert
                          JOIN incident ON incident.id = alert.incident_id
                        """ + where + """
                          GROUP BY incident.service_resource_id, alert.fingerprint
                          HAVING COUNT(DISTINCT incident.id) >= 2
                        ) recurrence_candidate
                        """)
                .param("start", window.start()).param("endExclusive", window.endExclusive())
                .param("serviceId", serviceFilter).query(Long.class).single();
        List<CandidateRow> rows = jdbcClient.sql("""
                        SELECT incident.service_resource_id, service.name AS service_name,
                               alert.fingerprint, MIN(alert.title) AS signal_title,
                               COUNT(DISTINCT incident.id) AS incident_count,
                               COUNT(DISTINCT CAST(incident.created_at AS DATE)) AS distinct_days,
                               MIN(incident.created_at) AS first_incident_at,
                               MAX(incident.created_at) AS latest_incident_at,
                               SUM(alert.occurrence_count) AS total_alert_occurrences,
                               COUNT(DISTINCT CASE WHEN incident.status NOT IN ('RESOLVED','CLOSED')
                                                   THEN incident.id END) AS active_incident_count,
                               MIN(CASE incident.severity WHEN 'P1' THEN 1 WHEN 'P2' THEN 2
                                                         WHEN 'P3' THEN 3 ELSE 4 END) AS severity_rank,
                               problem.id AS problem_id, problem.problem_code, problem.status AS problem_status,
                               problem.resolved_at AS problem_resolved_at,
                               CASE WHEN problem.id IS NULL THEN COUNT(DISTINCT incident.id)
                                    ELSE COUNT(DISTINCT CASE WHEN link.id IS NULL THEN incident.id END)
                               END AS unlinked_incident_count
                        FROM alert_event alert
                        JOIN incident ON incident.id = alert.incident_id
                        JOIN cmdb_resource service ON service.id = incident.service_resource_id
                        LEFT JOIN problem_record problem
                          ON problem.service_resource_id = incident.service_resource_id
                         AND problem.alert_fingerprint = alert.fingerprint
                        LEFT JOIN problem_incident_link link
                          ON link.problem_id = problem.id AND link.incident_id = incident.id
                        """ + where + """
                        GROUP BY incident.service_resource_id, service.name, alert.fingerprint,
                                 problem.id, problem.problem_code, problem.status, problem.resolved_at
                        HAVING COUNT(DISTINCT incident.id) >= 2
                        ORDER BY incident_count DESC, latest_incident_at DESC, alert.fingerprint
                        LIMIT :limit OFFSET :offset
                        """)
                .param("start", window.start()).param("endExclusive", window.endExclusive())
                .param("serviceId", serviceFilter).param("limit", safeSize)
                .param("offset", (safePage - 1) * safeSize)
                .query((rs, rowNum) -> new CandidateRow(
                        rs.getLong("service_resource_id"), rs.getString("service_name"),
                        rs.getString("fingerprint"), rs.getString("signal_title"),
                        rs.getLong("incident_count"), rs.getLong("distinct_days"),
                        rs.getObject("first_incident_at", LocalDateTime.class),
                        rs.getObject("latest_incident_at", LocalDateTime.class),
                        rs.getLong("total_alert_occurrences"), rs.getLong("active_incident_count"),
                        rs.getInt("severity_rank"), nullableLong(rs, "problem_id"),
                        rs.getString("problem_code"), rs.getString("problem_status"),
                        rs.getObject("problem_resolved_at", LocalDateTime.class),
                        rs.getLong("unlinked_incident_count")))
                .list();
        List<RecurrenceCandidate> items = rows.stream().map(row -> candidate(row, window)).toList();
        return new PageResponse<>(items, total, safePage, safeSize);
    }

    public PageResponse<ProblemView> list(String status, int page, int size) {
        String normalizedStatus = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!normalizedStatus.isBlank() && !STATUSES.contains(normalizedStatus)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PROBLEM_INVALID_STATUS",
                    "Problem 状态仅支持 OPEN、KNOWN_ERROR 或 RESOLVED");
        }
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(50, size));
        String where = "WHERE (:status = '' OR status = :status)";
        long total = jdbcClient.sql("SELECT COUNT(*) FROM problem_record " + where)
                .param("status", normalizedStatus).query(Long.class).single();
        List<Long> ids = jdbcClient.sql("""
                        SELECT id FROM problem_record
                        """ + where + """
                        ORDER BY CASE status WHEN 'OPEN' THEN 1 WHEN 'KNOWN_ERROR' THEN 2 ELSE 3 END,
                                 updated_at DESC, id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .param("status", normalizedStatus).param("limit", safeSize)
                .param("offset", (safePage - 1) * safeSize).query(Long.class).list();
        return new PageResponse<>(ids.stream().map(this::get).toList(), total, safePage, safeSize);
    }

    public ProblemView get(long id) {
        ProblemRow row = jdbcClient.sql("""
                        SELECT problem.id, problem.problem_code, problem.recurrence_key,
                               problem.service_resource_id, service.name AS service_name,
                               problem.alert_fingerprint, problem.title, problem.status,
                               problem.root_cause, problem.workaround, problem.resolution_summary,
                               problem.owner_id, owner.display_name AS owner_name,
                               problem.created_by, creator.display_name AS creator_name,
                               problem.resolved_by, resolver.display_name AS resolver_name,
                               problem.resolved_at, problem.version, problem.created_at, problem.updated_at,
                               COUNT(DISTINCT link.incident_id) AS incident_count,
                               MIN(incident.created_at) AS first_incident_at,
                               MAX(incident.created_at) AS latest_incident_at,
                               COUNT(DISTINCT CASE WHEN incident.status NOT IN ('RESOLVED','CLOSED')
                                                   THEN incident.id END) AS active_incident_count
                        FROM problem_record problem
                        JOIN cmdb_resource service ON service.id = problem.service_resource_id
                        JOIN sys_user owner ON owner.id = problem.owner_id
                        JOIN sys_user creator ON creator.id = problem.created_by
                        LEFT JOIN sys_user resolver ON resolver.id = problem.resolved_by
                        LEFT JOIN problem_incident_link link ON link.problem_id = problem.id
                        LEFT JOIN incident ON incident.id = link.incident_id
                        WHERE problem.id = :id
                        GROUP BY problem.id, problem.problem_code, problem.recurrence_key,
                                 problem.service_resource_id, service.name, problem.alert_fingerprint,
                                 problem.title, problem.status, problem.root_cause, problem.workaround,
                                 problem.resolution_summary, problem.owner_id, owner.display_name,
                                 problem.created_by, creator.display_name, problem.resolved_by,
                                 resolver.display_name, problem.resolved_at, problem.version,
                                 problem.created_at, problem.updated_at
                        """).param("id", id)
                .query((rs, rowNum) -> new ProblemRow(
                        rs.getLong("id"), rs.getString("problem_code"),
                        rs.getString("recurrence_key"), rs.getLong("service_resource_id"),
                        rs.getString("service_name"), rs.getString("alert_fingerprint"),
                        rs.getString("title"), rs.getString("status"), rs.getString("root_cause"),
                        rs.getString("workaround"), rs.getString("resolution_summary"),
                        rs.getLong("owner_id"), rs.getString("owner_name"),
                        rs.getLong("created_by"), rs.getString("creator_name"),
                        nullableLong(rs, "resolved_by"), rs.getString("resolver_name"),
                        rs.getObject("resolved_at", LocalDateTime.class), rs.getInt("version"),
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getObject("updated_at", LocalDateTime.class), rs.getLong("incident_count"),
                        rs.getObject("first_incident_at", LocalDateTime.class),
                        rs.getObject("latest_incident_at", LocalDateTime.class),
                        rs.getLong("active_incident_count")))
                .optional().orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "PROBLEM_NOT_FOUND", "Problem 不存在"));
        return view(row, linkedIncidents(id));
    }

    @Transactional
    public ProblemCreateResult create(String recurrenceKey, LocalDate from, LocalDate to,
                                       long actorId, String sourceIp) {
        Signature signature = signature(recurrenceKey);
        Window window = window(from, to, 90);
        Long existingId = findByRecurrence(recurrenceKey);
        if (existingId != null) {
            int linked = linkMatchingIncidents(existingId, signature, window, actorId);
            if (linked > 0) {
                auditService.recordAs(actorId, sourceIp, "PROBLEM_INCIDENTS_SYNCED",
                        "PROBLEM", existingId, "新增关联 Incident " + linked + " 个");
            }
            return new ProblemCreateResult(false, linked, get(existingId));
        }
        CandidateContext context = candidateContext(signature, window);
        ensureProblemOwner(actorId);
        String code = "PRB-" + LocalDateTime.now(BUSINESS_ZONE)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            jdbcClient.sql("""
                            INSERT INTO problem_record(
                              problem_code, recurrence_key, service_resource_id, alert_fingerprint,
                              title, root_cause, workaround, resolution_summary, owner_id, created_by)
                            VALUES (:code, :recurrenceKey, :serviceId, :fingerprint,
                              :title, '', '', '', :ownerId, :createdBy)
                            """).param("code", code).param("recurrenceKey", recurrenceKey)
                    .param("serviceId", signature.serviceId()).param("fingerprint", signature.fingerprint())
                    .param("title", generatedTitle(context))
                    .param("ownerId", actorId).param("createdBy", actorId).update();
        } catch (DuplicateKeyException exception) {
            Long concurrentId = findByRecurrence(recurrenceKey);
            if (concurrentId == null) throw exception;
            int linked = linkMatchingIncidents(concurrentId, signature, window, actorId);
            return new ProblemCreateResult(false, linked, get(concurrentId));
        }
        long problemId = jdbcClient.sql("SELECT id FROM problem_record WHERE recurrence_key = :key")
                .param("key", recurrenceKey).query(Long.class).single();
        int linked = linkMatchingIncidents(problemId, signature, window, actorId);
        auditService.recordAs(actorId, sourceIp, "PROBLEM_CREATED", "PROBLEM", problemId,
                "由 " + context.incidentCount() + " 个精确指纹匹配的 Incident 创建；已关联 " + linked + " 个");
        return new ProblemCreateResult(true, linked, get(problemId));
    }

    @Transactional
    public ProblemView update(long id, int expectedVersion, ProblemUpdate update,
                              long actorId, String sourceIp) {
        ProblemView current = get(id);
        ProblemStatus source = ProblemStatus.valueOf(current.status());
        ProblemStatus target = update.status() == null ? source : update.status();
        if (!source.canTransitionTo(target)) {
            throw new ApiException(HttpStatus.CONFLICT, "PROBLEM_INVALID_TRANSITION",
                    source + " 不能直接流转到 " + target);
        }
        long ownerId = update.ownerId() == null ? current.ownerId() : update.ownerId();
        ensureProblemOwner(ownerId);
        String title = choose(update.title(), current.title(), 240);
        if (title.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PROBLEM_TITLE_REQUIRED",
                    "Problem 标题不能为空");
        }
        String rootCause = choose(update.rootCause(), current.rootCause(), 4_000);
        String workaround = choose(update.workaround(), current.workaround(), 4_000);
        String resolutionSummary = choose(
                update.resolutionSummary(), current.resolutionSummary(), 2_000);
        if (target == ProblemStatus.KNOWN_ERROR
                && (rootCause.isBlank() || workaround.isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PROBLEM_KNOWN_ERROR_INCOMPLETE",
                    "进入已知错误前必须填写根因和规避方案");
        }
        if (target == ProblemStatus.RESOLVED && resolutionSummary.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PROBLEM_RESOLUTION_REQUIRED",
                    "解决 Problem 前必须填写解决说明");
        }
        int changed = jdbcClient.sql("""
                        UPDATE problem_record
                        SET title = :title, status = :status, root_cause = :rootCause,
                            workaround = :workaround, resolution_summary = :resolutionSummary,
                            owner_id = :ownerId,
                            resolved_at = CASE WHEN :status = 'RESOLVED'
                                               THEN COALESCE(resolved_at, CURRENT_TIMESTAMP) ELSE NULL END,
                            resolved_by = CASE WHEN :status = 'RESOLVED'
                                               THEN COALESCE(resolved_by, :actorId) ELSE NULL END,
                            version = version + 1, updated_at = CURRENT_TIMESTAMP
                        WHERE id = :id AND version = :version
                        """).param("title", title).param("status", target.name())
                .param("rootCause", rootCause).param("workaround", workaround)
                .param("resolutionSummary", resolutionSummary).param("ownerId", ownerId)
                .param("actorId", actorId).param("id", id).param("version", expectedVersion).update();
        if (changed == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "PROBLEM_VERSION_CONFLICT",
                    "Problem 已被其他人更新，请刷新后重试");
        }
        String action = action(source, target);
        auditService.recordAs(actorId, sourceIp, action, "PROBLEM", id,
                "状态 " + source + " -> " + target + "；版本 " + expectedVersion
                        + " -> " + (expectedVersion + 1));
        if (source != target) {
            addProblemTimeline(id, actorId, "Problem " + current.problemCode()
                    + " 状态变更：" + source + " -> " + target);
        }
        return get(id);
    }

    @Transactional
    public boolean linkMatchingProblem(long alertId, long incidentId) {
        List<ProblemRef> problems = jdbcClient.sql("""
                        SELECT problem.id, problem.problem_code
                        FROM problem_record problem
                        JOIN incident ON incident.id = :incidentId
                         AND incident.service_resource_id = problem.service_resource_id
                        JOIN alert_event alert ON alert.id = :alertId
                         AND alert.fingerprint = problem.alert_fingerprint
                        """).param("incidentId", incidentId).param("alertId", alertId)
                .query((rs, rowNum) -> new ProblemRef(
                        rs.getLong("id"), rs.getString("problem_code"))).list();
        boolean linked = false;
        for (ProblemRef problem : problems) {
            linked |= linkIncidentIfAbsent(problem.id(), problem.problemCode(), incidentId, null);
        }
        return linked;
    }

    private RecurrenceCandidate candidate(CandidateRow row, Window window) {
        String key = recurrenceKey(row.serviceId(), row.fingerprint());
        boolean recurredAfterResolution = row.problemResolvedAt() != null
                && row.latestIncidentAt().isAfter(row.problemResolvedAt());
        return new RecurrenceCandidate(key, "EXACT_ALERT_FINGERPRINT", row.serviceId(),
                row.serviceName(), row.signalTitle(), row.incidentCount(), row.distinctDays(),
                row.firstIncidentAt(), row.latestIncidentAt(), row.totalAlertOccurrences(),
                row.activeIncidentCount(), severity(row.severityRank()), row.problemId(),
                row.problemCode(), row.problemStatus(), row.unlinkedIncidentCount(),
                recurredAfterResolution,
                matchingIncidents(new Signature(row.serviceId(), row.fingerprint()), window, 8));
    }

    private CandidateContext candidateContext(Signature signature, Window window) {
        return jdbcClient.sql("""
                        SELECT service.name AS service_name, MIN(alert.title) AS signal_title,
                               COUNT(DISTINCT incident.id) AS incident_count
                        FROM alert_event alert
                        JOIN incident ON incident.id = alert.incident_id
                        JOIN cmdb_resource service ON service.id = incident.service_resource_id
                        WHERE incident.service_resource_id = :serviceId
                          AND alert.fingerprint = :fingerprint
                          AND incident.created_at >= :start
                          AND incident.created_at < :endExclusive
                        GROUP BY service.name
                        HAVING COUNT(DISTINCT incident.id) >= 2
                        """).param("serviceId", signature.serviceId())
                .param("fingerprint", signature.fingerprint()).param("start", window.start())
                .param("endExclusive", window.endExclusive())
                .query((rs, rowNum) -> new CandidateContext(
                        rs.getString("service_name"), rs.getString("signal_title"),
                        rs.getLong("incident_count")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                        "PROBLEM_RECURRENCE_NOT_CONFIRMED",
                        "当前窗口内至少需要两个不同 Incident 的精确指纹证据"));
    }

    private int linkMatchingIncidents(long problemId, Signature signature,
                                      Window window, Long actorId) {
        String problemCode = jdbcClient.sql("SELECT problem_code FROM problem_record WHERE id = :id")
                .param("id", problemId).query(String.class).single();
        List<Long> incidentIds = jdbcClient.sql("""
                        SELECT DISTINCT incident.id
                        FROM incident
                        JOIN alert_event alert ON alert.incident_id = incident.id
                        WHERE incident.service_resource_id = :serviceId
                          AND alert.fingerprint = :fingerprint
                          AND incident.created_at >= :start
                          AND incident.created_at < :endExclusive
                        ORDER BY incident.id
                        """).param("serviceId", signature.serviceId())
                .param("fingerprint", signature.fingerprint()).param("start", window.start())
                .param("endExclusive", window.endExclusive()).query(Long.class).list();
        int linked = 0;
        for (Long incidentId : incidentIds) {
            if (linkIncidentIfAbsent(problemId, problemCode, incidentId, actorId)) linked++;
        }
        return linked;
    }

    private boolean linkIncidentIfAbsent(long problemId, String problemCode,
                                         long incidentId, Long actorId) {
        long existing = jdbcClient.sql("""
                        SELECT COUNT(*) FROM problem_incident_link
                        WHERE problem_id = :problemId AND incident_id = :incidentId
                        """).param("problemId", problemId).param("incidentId", incidentId)
                .query(Long.class).single();
        if (existing > 0) return false;
        try {
            jdbcClient.sql("""
                            INSERT INTO problem_incident_link(
                              problem_id, incident_id, link_reason, linked_by)
                            VALUES (:problemId, :incidentId, 'EXACT_ALERT_FINGERPRINT', :actorId)
                            """).param("problemId", problemId).param("incidentId", incidentId)
                    .param("actorId", actorId).update();
        } catch (DuplicateKeyException exception) {
            return false;
        }
        jdbcClient.sql("""
                        INSERT INTO incident_timeline(
                          incident_id, event_type, actor_id, content, evidence_ref, created_at)
                        VALUES (:incidentId, 'PROBLEM_LINKED', :actorId, :content, :evidenceRef, :createdAt)
                        """).param("incidentId", incidentId).param("actorId", actorId)
                .param("content", "关联 Problem " + problemCode + "；依据：相同告警指纹")
                .param("evidenceRef", "problem:" + problemId)
                .param("createdAt", LocalDateTime.now(BUSINESS_ZONE)).update();
        return true;
    }

    private List<IncidentRef> matchingIncidents(Signature signature, Window window, int limit) {
        return jdbcClient.sql("""
                        SELECT DISTINCT incident.id, incident.incident_code, incident.title,
                               incident.severity, incident.status, incident.created_at,
                               incident.resolved_at
                        FROM incident
                        JOIN alert_event alert ON alert.incident_id = incident.id
                        WHERE incident.service_resource_id = :serviceId
                          AND alert.fingerprint = :fingerprint
                          AND incident.created_at >= :start
                          AND incident.created_at < :endExclusive
                        ORDER BY incident.created_at DESC, incident.id DESC
                        LIMIT :limit
                        """).param("serviceId", signature.serviceId())
                .param("fingerprint", signature.fingerprint()).param("start", window.start())
                .param("endExclusive", window.endExclusive()).param("limit", limit)
                .query(ProblemService::mapIncident).list();
    }

    private List<IncidentRef> linkedIncidents(long problemId) {
        return jdbcClient.sql("""
                        SELECT incident.id, incident.incident_code, incident.title,
                               incident.severity, incident.status, incident.created_at,
                               incident.resolved_at
                        FROM problem_incident_link link
                        JOIN incident ON incident.id = link.incident_id
                        WHERE link.problem_id = :problemId
                        ORDER BY incident.created_at DESC, incident.id DESC
                        """).param("problemId", problemId).query(ProblemService::mapIncident).list();
    }

    private void addProblemTimeline(long problemId, Long actorId, String content) {
        List<Long> incidentIds = jdbcClient.sql("""
                        SELECT incident_id FROM problem_incident_link
                        WHERE problem_id = :problemId ORDER BY incident_id
                        """).param("problemId", problemId).query(Long.class).list();
        for (Long incidentId : incidentIds) {
            jdbcClient.sql("""
                            INSERT INTO incident_timeline(
                              incident_id, event_type, actor_id, content, evidence_ref, created_at)
                            VALUES (:incidentId, 'PROBLEM_STATUS_CHANGED', :actorId,
                              :content, :evidenceRef, :createdAt)
                            """).param("incidentId", incidentId).param("actorId", actorId)
                    .param("content", content).param("evidenceRef", "problem:" + problemId)
                    .param("createdAt", LocalDateTime.now(BUSINESS_ZONE)).update();
        }
    }

    private void ensureProblemOwner(long ownerId) {
        long count = jdbcClient.sql("""
                        SELECT COUNT(*) FROM sys_user
                        WHERE id = :id AND status = 'ACTIVE'
                          AND role_code IN ('ADMIN','OPS_MANAGER')
                        """).param("id", ownerId).query(Long.class).single();
        if (count == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PROBLEM_OWNER_INVALID",
                    "Problem 负责人必须是启用的管理员或运维经理");
        }
    }

    private Long findByRecurrence(String recurrenceKey) {
        return jdbcClient.sql("SELECT id FROM problem_record WHERE recurrence_key = :key")
                .param("key", recurrenceKey).query(Long.class).optional().orElse(null);
    }

    private static ProblemView view(ProblemRow row, List<IncidentRef> incidents) {
        boolean recurred = row.resolvedAt() != null && row.latestIncidentAt() != null
                && row.latestIncidentAt().isAfter(row.resolvedAt());
        return new ProblemView(row.id(), row.problemCode(), row.recurrenceKey(),
                "EXACT_ALERT_FINGERPRINT", row.serviceId(), row.serviceName(), row.title(),
                row.status(), row.rootCause(), row.workaround(), row.resolutionSummary(),
                row.ownerId(), row.ownerName(), row.creatorId(), row.creatorName(),
                row.resolverId(), row.resolverName(), row.resolvedAt(), row.version(),
                row.createdAt(), row.updatedAt(), row.incidentCount(), row.firstIncidentAt(),
                row.latestIncidentAt(), row.activeIncidentCount(), recurred, incidents);
    }

    private static IncidentRef mapIncident(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new IncidentRef(rs.getLong("id"), rs.getString("incident_code"),
                rs.getString("title"), rs.getString("severity"), rs.getString("status"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("resolved_at", LocalDateTime.class));
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Window window(LocalDate from, LocalDate to, int defaultDays) {
        LocalDate effectiveTo = to == null ? LocalDate.now(BUSINESS_ZONE) : to;
        LocalDate effectiveFrom = from == null ? effectiveTo.minusDays(defaultDays - 1L) : from;
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PROBLEM_INVALID_WINDOW",
                    "开始日期不能晚于结束日期");
        }
        if (ChronoUnit.DAYS.between(effectiveFrom, effectiveTo) > 365) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PROBLEM_WINDOW_TOO_LARGE",
                    "重复事故窗口最多包含 366 个自然日");
        }
        return new Window(effectiveFrom, effectiveTo, effectiveFrom.atStartOfDay(),
                effectiveTo.plusDays(1).atStartOfDay());
    }

    private static Signature signature(String recurrenceKey) {
        if (recurrenceKey == null || !recurrenceKey.matches("[1-9][0-9]*:[0-9a-f]{64}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PROBLEM_INVALID_RECURRENCE_KEY",
                    "重复事故标识格式无效");
        }
        int separator = recurrenceKey.indexOf(':');
        try {
            return new Signature(Long.parseLong(recurrenceKey.substring(0, separator)),
                    recurrenceKey.substring(separator + 1));
        } catch (NumberFormatException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PROBLEM_INVALID_RECURRENCE_KEY",
                    "重复事故标识格式无效");
        }
    }

    private static String recurrenceKey(long serviceId, String fingerprint) {
        return serviceId + ":" + fingerprint;
    }

    private static String generatedTitle(CandidateContext context) {
        String title = context.serviceName() + " 重复故障：" + context.signalTitle();
        if (title.length() <= 240) return title;
        int end = Character.isHighSurrogate(title.charAt(238)) ? 238 : 239;
        return title.substring(0, end) + "…";
    }

    private static String choose(String requested, String current, int maxLength) {
        if (requested == null) return current == null ? "" : current;
        String normalized = requested.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private static String action(ProblemStatus source, ProblemStatus target) {
        if (source == ProblemStatus.RESOLVED && target == ProblemStatus.OPEN) {
            return "PROBLEM_REOPENED";
        }
        return switch (target) {
            case OPEN -> "PROBLEM_UPDATED";
            case KNOWN_ERROR -> "PROBLEM_KNOWN_ERROR_RECORDED";
            case RESOLVED -> "PROBLEM_RESOLVED";
        };
    }

    private static String severity(int rank) {
        return switch (rank) {
            case 1 -> "P1";
            case 2 -> "P2";
            case 3 -> "P3";
            default -> "P4";
        };
    }

    public record RecurrenceCandidate(
            String recurrenceKey, String matchReason, long serviceId, String serviceName,
            String signalTitle, long incidentCount, long distinctDays,
            LocalDateTime firstIncidentAt, LocalDateTime latestIncidentAt,
            long totalAlertOccurrences, long activeIncidentCount, String highestSeverity,
            Long problemId, String problemCode, String problemStatus,
            long unlinkedIncidentCount, boolean recurredAfterResolution,
            List<IncidentRef> incidents) {
    }

    public record ProblemCreateResult(boolean created, int newlyLinkedIncidents, ProblemView problem) {
    }

    public record ProblemUpdate(String title, ProblemStatus status, Long ownerId,
                                String rootCause, String workaround, String resolutionSummary) {
    }

    public record ProblemView(
            long id, String problemCode, String recurrenceKey, String matchReason,
            long serviceId, String serviceName, String title, String status,
            String rootCause, String workaround, String resolutionSummary,
            long ownerId, String ownerName, long creatorId, String creatorName,
            Long resolverId, String resolverName, LocalDateTime resolvedAt, int version,
            LocalDateTime createdAt, LocalDateTime updatedAt, long incidentCount,
            LocalDateTime firstIncidentAt, LocalDateTime latestIncidentAt,
            long activeIncidentCount, boolean recurredAfterResolution,
            List<IncidentRef> incidents) {
    }

    public record IncidentRef(long id, String incidentCode, String title, String severity,
                              String status, LocalDateTime createdAt, LocalDateTime resolvedAt) {
    }

    private record Window(LocalDate from, LocalDate to,
                          LocalDateTime start, LocalDateTime endExclusive) {
    }

    private record Signature(long serviceId, String fingerprint) {
    }

    private record CandidateContext(String serviceName, String signalTitle, long incidentCount) {
    }

    private record ProblemRef(long id, String problemCode) {
    }

    private record CandidateRow(
            long serviceId, String serviceName, String fingerprint, String signalTitle,
            long incidentCount, long distinctDays, LocalDateTime firstIncidentAt,
            LocalDateTime latestIncidentAt, long totalAlertOccurrences,
            long activeIncidentCount, int severityRank, Long problemId, String problemCode,
            String problemStatus, LocalDateTime problemResolvedAt, long unlinkedIncidentCount) {
    }

    private record ProblemRow(
            long id, String problemCode, String recurrenceKey, long serviceId, String serviceName,
            String alertFingerprint, String title, String status, String rootCause,
            String workaround, String resolutionSummary, long ownerId, String ownerName,
            long creatorId, String creatorName, Long resolverId, String resolverName,
            LocalDateTime resolvedAt, int version, LocalDateTime createdAt,
            LocalDateTime updatedAt, long incidentCount, LocalDateTime firstIncidentAt,
            LocalDateTime latestIncidentAt, long activeIncidentCount) {
    }
}
