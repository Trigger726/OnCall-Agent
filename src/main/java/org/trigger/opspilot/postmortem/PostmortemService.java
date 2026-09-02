package org.trigger.opspilot.postmortem;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.trigger.opspilot.audit.AuditService;
import org.trigger.opspilot.common.ApiException;
import org.trigger.opspilot.observability.LogRedactor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PostmortemService {
    private static final String DRAFT_MARKER = "【待补充】";
    private static final Set<String> REVIEWABLE_STATUSES = Set.of("RESOLVED", "CLOSED");

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final LogRedactor logRedactor;

    public PostmortemService(JdbcClient jdbcClient, ObjectMapper objectMapper,
                             AuditService auditService, LogRedactor logRedactor) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.logRedactor = logRedactor;
    }

    public PostmortemView findByIncident(long incidentId) {
        requireIncident(incidentId);
        return jdbcClient.sql(baseSelect() + " WHERE postmortem.incident_id = :incidentId")
                .param("incidentId", incidentId).query(PostmortemService::mapRow).optional()
                .map(this::withFollowUps).orElse(null);
    }

    @Transactional
    public PostmortemView createDraft(long incidentId, long creatorId) {
        IncidentContext incident = requireIncident(incidentId);
        lockReviewableIncident(incidentId);
        PostmortemView existing = findExistingByIncident(incidentId);
        if (existing != null) return existing;

        ReportContext report = latestReport(incidentId);
        List<TimelineSnapshot> timeline = timelineSnapshot(incidentId);
        List<String> evidenceRefs = evidenceRefs(incidentId, report, timeline);
        String summary = report == null ? incident.description() : report.summary();
        String impact = incident.severity() + " 事故影响 " + incident.resourceName()
                + "；关联 " + incident.alertCount() + " 条告警，累计 " + incident.occurrenceCount()
                + " 次事件；恢复时间 " + incident.resolvedAt() + "。";
        String rootCause = report == null
                ? DRAFT_MARKER + "基于证据说明直接原因和系统性原因。" : report.hypothesis();
        String changes = relatedChanges(incident);
        String contributingFactors = changes.isBlank()
                ? DRAFT_MARKER + "补充放大影响的流程、容量、监控或依赖因素。" : changes;
        String lessons = DRAFT_MARKER + "说明哪些响应机制有效、哪些环节需要改进。";

        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbcClient.sql("""
                            INSERT INTO incident_postmortem(
                              incident_id, summary, customer_impact, root_cause,
                              contributing_factors, lessons_learned,
                              timeline_snapshot_json, evidence_refs_json, created_by)
                            VALUES (:incidentId, :summary, :impact, :rootCause,
                              :factors, :lessons, :timeline, :evidenceRefs, :creatorId)
                            """)
                    .param("incidentId", incidentId)
                    .param("summary", normalize(summary, 4_000))
                    .param("impact", normalize(impact, 4_000))
                    .param("rootCause", normalize(rootCause, 4_000))
                    .param("factors", normalize(contributingFactors, 4_000))
                    .param("lessons", normalize(lessons, 4_000))
                    .param("timeline", json(timeline)).param("evidenceRefs", json(evidenceRefs))
                    .param("creatorId", creatorId).update(keyHolder, "id");
        } catch (DuplicateKeyException exception) {
            PostmortemView concurrent = findExistingByIncident(incidentId);
            if (concurrent != null) return concurrent;
            throw exception;
        }
        if (keyHolder.getKey() == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "POSTMORTEM_CREATE_FAILED", "无法创建复盘草稿");
        }
        long postmortemId = keyHolder.getKey().longValue();
        addTimeline(incidentId, "POSTMORTEM_CREATED", creatorId,
                "已从 Incident 时间线和调查证据生成无责复盘草稿",
                "postmortem:" + postmortemId);
        auditService.record("POSTMORTEM_CREATED", "INCIDENT_POSTMORTEM", postmortemId,
                "为 " + incident.incidentCode() + " 创建证据快照草稿");
        return get(postmortemId);
    }

    @Transactional
    public PostmortemView update(long postmortemId, int expectedVersion, DraftContent content) {
        PostmortemView current = get(postmortemId);
        requireDraft(current);
        int updated = jdbcClient.sql("""
                        UPDATE incident_postmortem
                        SET summary = :summary, customer_impact = :impact, root_cause = :rootCause,
                            contributing_factors = :factors, lessons_learned = :lessons,
                            version = version + 1, updated_at = CURRENT_TIMESTAMP
                        WHERE id = :id AND version = :version AND status = 'DRAFT'
                        """)
                .param("summary", normalize(content.summary(), 4_000))
                .param("impact", normalize(content.customerImpact(), 4_000))
                .param("rootCause", normalize(content.rootCause(), 4_000))
                .param("factors", normalize(content.contributingFactors(), 4_000))
                .param("lessons", normalize(content.lessonsLearned(), 4_000))
                .param("id", postmortemId).param("version", expectedVersion).update();
        if (updated == 0) throw versionConflict();
        auditService.record("POSTMORTEM_UPDATED", "INCIDENT_POSTMORTEM", postmortemId,
                "复盘草稿版本 " + expectedVersion + " -> " + (expectedVersion + 1));
        return get(postmortemId);
    }

    @Transactional
    public PostmortemView submit(long postmortemId, int expectedVersion, long submitterId) {
        PostmortemView current = get(postmortemId);
        requireDraft(current);
        lockReviewableIncident(current.incidentId());
        validateReadyForReview(current);
        int updated = jdbcClient.sql("""
                        UPDATE incident_postmortem
                        SET status = 'IN_REVIEW', submitted_by = :submitterId,
                            submitted_at = CURRENT_TIMESTAMP, version = version + 1,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :id AND version = :version AND status = 'DRAFT'
                        """).param("submitterId", submitterId).param("id", postmortemId)
                .param("version", expectedVersion).update();
        if (updated == 0) throw versionConflict();
        addTimeline(current.incidentId(), "POSTMORTEM_SUBMITTED", submitterId,
                "复盘已提交独立复核", "postmortem:" + postmortemId);
        auditService.record("POSTMORTEM_SUBMITTED", "INCIDENT_POSTMORTEM", postmortemId,
                "提交复盘版本 " + expectedVersion + " 进行独立复核");
        return get(postmortemId);
    }

    @Transactional
    public PostmortemView review(long postmortemId, int expectedVersion, long reviewerId,
                                 ReviewDecision decision, String comment) {
        PostmortemView current = get(postmortemId);
        if (!"IN_REVIEW".equals(current.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "POSTMORTEM_NOT_IN_REVIEW", "复盘当前不在待复核状态");
        }
        if (Objects.equals(current.submittedById(), reviewerId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "POSTMORTEM_SELF_REVIEW_FORBIDDEN",
                    "复盘提交人不能复核自己的内容");
        }
        if (decision == ReviewDecision.PUBLISH) {
            lockReviewableIncident(current.incidentId());
        }
        String targetStatus = decision == ReviewDecision.PUBLISH ? "PUBLISHED" : "DRAFT";
        int updated = jdbcClient.sql("""
                        UPDATE incident_postmortem
                        SET status = :status, reviewed_by = :reviewerId,
                            review_comment = :comment, reviewed_at = CURRENT_TIMESTAMP,
                            published_at = CASE WHEN :status = 'PUBLISHED' THEN CURRENT_TIMESTAMP ELSE NULL END,
                            version = version + 1, updated_at = CURRENT_TIMESTAMP
                        WHERE id = :id AND version = :version AND status = 'IN_REVIEW'
                        """).param("status", targetStatus).param("reviewerId", reviewerId)
                .param("comment", normalize(comment, 500)).param("id", postmortemId)
                .param("version", expectedVersion).update();
        if (updated == 0) throw versionConflict();
        String eventType = decision == ReviewDecision.PUBLISH
                ? "POSTMORTEM_PUBLISHED" : "POSTMORTEM_CHANGES_REQUESTED";
        String message = decision == ReviewDecision.PUBLISH
                ? "复盘已通过独立复核并发布" : "复盘复核要求修改：" + normalize(comment, 300);
        addTimeline(current.incidentId(), eventType, reviewerId, message, "postmortem:" + postmortemId);
        auditService.record("POSTMORTEM_" + decision.name(), "INCIDENT_POSTMORTEM", postmortemId,
                "复核版本 " + expectedVersion + " -> " + (expectedVersion + 1));
        return get(postmortemId);
    }

    @Transactional
    public PostmortemView addFollowUp(long postmortemId, int expectedPostmortemVersion,
                                      long creatorId, FollowUpContent content) {
        PostmortemView current = get(postmortemId);
        requireDraft(current);
        String ownerName = requireActiveOwner(content.ownerId());
        validateDueDate(content.dueDate());
        touchDraft(postmortemId, expectedPostmortemVersion);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql("""
                        INSERT INTO postmortem_follow_up(
                          postmortem_id, title, description, priority, owner_id, due_date, created_by)
                        VALUES (:postmortemId, :title, :description, :priority, :ownerId, :dueDate, :creatorId)
                        """).param("postmortemId", postmortemId)
                .param("title", normalize(content.title(), 240))
                .param("description", normalize(content.description(), 1_000))
                .param("priority", content.priority().name()).param("ownerId", content.ownerId())
                .param("dueDate", content.dueDate()).param("creatorId", creatorId)
                .update(keyHolder, "id");
        if (keyHolder.getKey() == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "POSTMORTEM_FOLLOW_UP_CREATE_FAILED", "无法创建复盘行动项");
        }
        long followUpId = keyHolder.getKey().longValue();
        addTimeline(current.incidentId(), "FOLLOW_UP_CREATED", creatorId,
                "新增复盘行动项：" + normalize(content.title(), 160) + "；负责人 " + ownerName,
                "postmortem-follow-up:" + followUpId);
        auditService.record("POSTMORTEM_FOLLOW_UP_CREATED", "POSTMORTEM_FOLLOW_UP", followUpId,
                "负责人 " + ownerName + "，截止 " + content.dueDate());
        return get(postmortemId);
    }

    @Transactional
    public PostmortemView updateFollowUp(long followUpId, int expectedPostmortemVersion,
                                         int expectedVersion, FollowUpContent content) {
        FollowUpView followUp = getFollowUp(followUpId);
        PostmortemView current = get(followUp.postmortemId());
        requireDraft(current);
        requireActiveOwner(content.ownerId());
        validateDueDate(content.dueDate());
        touchDraft(current.id(), expectedPostmortemVersion);
        int updated = jdbcClient.sql("""
                        UPDATE postmortem_follow_up
                        SET title = :title, description = :description, priority = :priority,
                            owner_id = :ownerId, due_date = :dueDate,
                            version = version + 1, updated_at = CURRENT_TIMESTAMP
                        WHERE id = :id AND version = :version AND status = 'OPEN'
                        """).param("title", normalize(content.title(), 240))
                .param("description", normalize(content.description(), 1_000))
                .param("priority", content.priority().name()).param("ownerId", content.ownerId())
                .param("dueDate", content.dueDate()).param("id", followUpId)
                .param("version", expectedVersion).update();
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "POSTMORTEM_FOLLOW_UP_VERSION_CONFLICT",
                    "行动项已被修改或完成，请刷新后重试");
        }
        auditService.record("POSTMORTEM_FOLLOW_UP_UPDATED", "POSTMORTEM_FOLLOW_UP", followUpId,
                "行动项版本 " + expectedVersion + " -> " + (expectedVersion + 1));
        return get(current.id());
    }

    private void touchDraft(long postmortemId, int expectedVersion) {
        int updated = jdbcClient.sql("""
                        UPDATE incident_postmortem
                        SET version = version + 1, updated_at = CURRENT_TIMESTAMP
                        WHERE id = :id AND version = :version AND status = 'DRAFT'
                        """).param("id", postmortemId).param("version", expectedVersion).update();
        if (updated == 0) throw versionConflict();
    }

    @Transactional
    public PostmortemView completeFollowUp(long followUpId, int expectedVersion,
                                           long actorId, String actorRole) {
        FollowUpView followUp = getFollowUp(followUpId);
        boolean elevated = "ADMIN".equals(actorRole) || "OPS_MANAGER".equals(actorRole);
        if (followUp.ownerId() != actorId && !elevated) {
            throw new ApiException(HttpStatus.FORBIDDEN, "POSTMORTEM_FOLLOW_UP_NOT_OWNER",
                    "只有负责人、管理员或运维经理可以完成行动项");
        }
        int updated = jdbcClient.sql("""
                        UPDATE postmortem_follow_up
                        SET status = 'DONE', completed_by = :actorId, completed_at = CURRENT_TIMESTAMP,
                            version = version + 1, updated_at = CURRENT_TIMESTAMP
                        WHERE id = :id AND version = :version AND status = 'OPEN'
                        """).param("actorId", actorId).param("id", followUpId)
                .param("version", expectedVersion).update();
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "POSTMORTEM_FOLLOW_UP_VERSION_CONFLICT",
                    "行动项已被修改或完成，请刷新后重试");
        }
        PostmortemView current = get(followUp.postmortemId());
        addTimeline(current.incidentId(), "FOLLOW_UP_COMPLETED", actorId,
                "完成复盘行动项：" + followUp.title(), "postmortem-follow-up:" + followUpId);
        auditService.record("POSTMORTEM_FOLLOW_UP_COMPLETED", "POSTMORTEM_FOLLOW_UP", followUpId,
                "完成行动项版本 " + expectedVersion + " -> " + (expectedVersion + 1));
        return get(current.id());
    }

    public PostmortemView get(long postmortemId) {
        return jdbcClient.sql(baseSelect() + " WHERE postmortem.id = :id")
                .param("id", postmortemId).query(PostmortemService::mapRow).optional()
                .map(this::withFollowUps)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "POSTMORTEM_NOT_FOUND", "复盘不存在"));
    }

    private PostmortemView findExistingByIncident(long incidentId) {
        return jdbcClient.sql(baseSelect() + " WHERE postmortem.incident_id = :incidentId")
                .param("incidentId", incidentId).query(PostmortemService::mapRow).optional()
                .map(this::withFollowUps).orElse(null);
    }

    private PostmortemView withFollowUps(PostmortemRow row) {
        List<FollowUpView> followUps = jdbcClient.sql(followUpSelect()
                        + " WHERE follow_up.postmortem_id = :postmortemId"
                        + " ORDER BY CASE follow_up.status WHEN 'OPEN' THEN 1 ELSE 2 END,"
                        + " follow_up.due_date, follow_up.id")
                .param("postmortemId", row.id()).query(PostmortemService::mapFollowUp).list();
        return new PostmortemView(row.id(), row.incidentId(), row.incidentCode(), row.incidentTitle(),
                row.severity(), row.status(), row.summary(), row.customerImpact(), row.rootCause(),
                row.contributingFactors(), row.lessonsLearned(), parseJson(row.timelineSnapshotJson()),
                parseJson(row.evidenceRefsJson()), row.createdById(), row.createdByName(),
                row.submittedById(), row.submittedByName(), row.reviewedById(), row.reviewedByName(),
                row.reviewComment(), row.submittedAt(), row.reviewedAt(), row.publishedAt(),
                row.version(), row.createdAt(), row.updatedAt(), followUps);
    }

    private FollowUpView getFollowUp(long followUpId) {
        return jdbcClient.sql(followUpSelect() + " WHERE follow_up.id = :id")
                .param("id", followUpId).query(PostmortemService::mapFollowUp).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "POSTMORTEM_FOLLOW_UP_NOT_FOUND", "复盘行动项不存在"));
    }

    private IncidentContext requireIncident(long incidentId) {
        return jdbcClient.sql("""
                        SELECT incident.id, incident.incident_code, incident.title, incident.description,
                               incident.severity, incident.status, incident.service_resource_id,
                               resource.name AS resource_name, incident.created_at,
                               COALESCE(incident.resolved_at, incident.updated_at) AS resolved_at,
                               (SELECT COUNT(*) FROM alert_event WHERE incident_id = incident.id) AS alert_count,
                               (SELECT COALESCE(SUM(occurrence_count), 0) FROM alert_event
                                WHERE incident_id = incident.id) AS occurrence_count
                        FROM incident JOIN cmdb_resource resource
                          ON resource.id = incident.service_resource_id
                        WHERE incident.id = :id
                        """).param("id", incidentId)
                .query((rs, rowNum) -> new IncidentContext(
                        rs.getLong("id"), rs.getString("incident_code"), rs.getString("title"),
                        rs.getString("description"), rs.getString("severity"), rs.getString("status"),
                        rs.getLong("service_resource_id"), rs.getString("resource_name"),
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getObject("resolved_at", LocalDateTime.class), rs.getInt("alert_count"),
                        rs.getInt("occurrence_count")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "INCIDENT_NOT_FOUND", "Incident 不存在"));
    }

    private void lockReviewableIncident(long incidentId) {
        String status = jdbcClient.sql("SELECT status FROM incident WHERE id = :id FOR UPDATE")
                .param("id", incidentId).query(String.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "INCIDENT_NOT_FOUND", "Incident 不存在"));
        if (!REVIEWABLE_STATUSES.contains(status)) {
            throw new ApiException(HttpStatus.CONFLICT, "POSTMORTEM_INCIDENT_NOT_RESOLVED",
                    "Incident 恢复或关闭后才能创建、提交或发布复盘");
        }
    }

    private ReportContext latestReport(long incidentId) {
        return jdbcClient.sql("""
                        SELECT id, summary, hypothesis FROM investigation_report
                        WHERE incident_id = :incidentId AND status IN ('COMPLETED', 'PARTIAL')
                        ORDER BY created_at DESC, id DESC LIMIT 1
                        """).param("incidentId", incidentId)
                .query((rs, rowNum) -> new ReportContext(
                        rs.getLong("id"), rs.getString("summary"), rs.getString("hypothesis")))
                .optional().orElse(null);
    }

    private List<TimelineSnapshot> timelineSnapshot(long incidentId) {
        List<TimelineSnapshot> timeline = jdbcClient.sql("""
                        SELECT timeline.event_type, timeline.content, timeline.evidence_ref,
                               actor.display_name AS actor_name, timeline.created_at
                        FROM incident_timeline timeline
                        LEFT JOIN sys_user actor ON actor.id = timeline.actor_id
                        WHERE timeline.incident_id = :incidentId
                        ORDER BY timeline.created_at, timeline.id
                        """).param("incidentId", incidentId)
                .query((rs, rowNum) -> new TimelineSnapshot(
                        rs.getString("event_type"), rs.getString("content"),
                        rs.getString("evidence_ref"), rs.getString("actor_name"),
                        rs.getObject("created_at", LocalDateTime.class))).list();
        return timeline.stream().map(item -> new TimelineSnapshot(item.eventType(),
                logRedactor.redact(item.content()), logRedactor.redact(item.evidenceRef()),
                item.actor(), item.createdAt()))
                .toList();
    }

    private List<String> evidenceRefs(long incidentId, ReportContext report,
                                      List<TimelineSnapshot> timeline) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        jdbcClient.sql("SELECT id FROM alert_event WHERE incident_id = :incidentId ORDER BY id")
                .param("incidentId", incidentId).query(Long.class).list()
                .forEach(id -> refs.add("alert:" + id));
        if (report != null) refs.add("investigation-report:" + report.id());
        timeline.stream().map(TimelineSnapshot::evidenceRef).filter(Objects::nonNull)
                .filter(ref -> !ref.isBlank()).forEach(refs::add);
        return List.copyOf(refs);
    }

    private String relatedChanges(IncidentContext incident) {
        List<ChangeContext> changes = jdbcClient.sql("""
                        SELECT change_code, summary, started_at FROM change_record
                        WHERE resource_id = :resourceId
                          AND started_at BETWEEN :fromTime AND :toTime
                        ORDER BY started_at DESC
                        """).param("resourceId", incident.resourceId())
                .param("fromTime", incident.createdAt().minusHours(24))
                .param("toTime", incident.resolvedAt() == null
                        ? incident.createdAt().plusHours(24) : incident.resolvedAt())
                .query((rs, rowNum) -> new ChangeContext(rs.getString("change_code"),
                        rs.getString("summary"), rs.getObject("started_at", LocalDateTime.class))).list();
        return changes.stream().map(change -> "相关变更 " + change.code() + "（" + change.startedAt()
                        + "）：" + change.summary())
                .collect(Collectors.joining("\n"));
    }

    private void validateReadyForReview(PostmortemView postmortem) {
        List<String> fields = List.of(postmortem.summary(), postmortem.customerImpact(),
                postmortem.rootCause(), postmortem.contributingFactors(), postmortem.lessonsLearned());
        if (fields.stream().anyMatch(value -> value == null || value.isBlank()
                || value.contains(DRAFT_MARKER))) {
            throw new ApiException(HttpStatus.CONFLICT, "POSTMORTEM_INCOMPLETE",
                    "复盘仍有待补充字段，不能提交复核");
        }
        if (postmortem.followUps().isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "POSTMORTEM_FOLLOW_UP_REQUIRED",
                    "至少需要一个带负责人和截止日期的防复发行动项");
        }
    }

    private void requireDraft(PostmortemView postmortem) {
        if (!"DRAFT".equals(postmortem.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "POSTMORTEM_NOT_EDITABLE",
                    "只有草稿状态的复盘可以编辑");
        }
    }

    private String requireActiveOwner(long ownerId) {
        return jdbcClient.sql("""
                        SELECT display_name FROM sys_user
                        WHERE id = :id AND status = 'ACTIVE'
                          AND role_code IN ('ADMIN', 'OPS_MANAGER', 'ON_CALL')
                        """)
                .param("id", ownerId).query(String.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "POSTMORTEM_FOLLOW_UP_OWNER_NOT_ELIGIBLE",
                        "行动项负责人不存在、已停用或没有运维处置权限"));
    }

    private void validateDueDate(LocalDate dueDate) {
        if (dueDate == null || dueDate.isBefore(LocalDate.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "POSTMORTEM_FOLLOW_UP_DUE_DATE_INVALID",
                    "行动项截止日期不能早于今天");
        }
    }

    private void addTimeline(long incidentId, String eventType, long actorId,
                             String content, String evidenceRef) {
        jdbcClient.sql("""
                        INSERT INTO incident_timeline(incident_id, event_type, actor_id, content, evidence_ref)
                        VALUES (:incidentId, :eventType, :actorId, :content, :evidenceRef)
                        """).param("incidentId", incidentId).param("eventType", eventType)
                .param("actorId", actorId).param("content", content)
                .param("evidenceRef", evidenceRef).update();
    }

    private String normalize(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "POSTMORTEM_CONTENT_REQUIRED", "复盘字段不能为空");
        }
        String normalized = logRedactor.redact(value.trim());
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private ApiException versionConflict() {
        return new ApiException(HttpStatus.CONFLICT, "POSTMORTEM_VERSION_CONFLICT",
                "复盘已被其他人更新，请刷新后重试");
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "POSTMORTEM_SNAPSHOT_FAILED", "无法保存复盘证据快照");
        }
    }

    private JsonNode parseJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            return objectMapper.createArrayNode();
        }
    }

    private static String baseSelect() {
        return """
                SELECT postmortem.id, postmortem.incident_id, incident.incident_code,
                       incident.title AS incident_title, incident.severity,
                       postmortem.status, postmortem.summary, postmortem.customer_impact,
                       postmortem.root_cause, postmortem.contributing_factors,
                       postmortem.lessons_learned, postmortem.timeline_snapshot_json,
                       postmortem.evidence_refs_json, postmortem.created_by,
                       creator.display_name AS created_by_name, postmortem.submitted_by,
                       submitter.display_name AS submitted_by_name, postmortem.reviewed_by,
                       reviewer.display_name AS reviewed_by_name, postmortem.review_comment,
                       postmortem.submitted_at, postmortem.reviewed_at, postmortem.published_at,
                       postmortem.version, postmortem.created_at, postmortem.updated_at
                FROM incident_postmortem postmortem
                JOIN incident ON incident.id = postmortem.incident_id
                JOIN sys_user creator ON creator.id = postmortem.created_by
                LEFT JOIN sys_user submitter ON submitter.id = postmortem.submitted_by
                LEFT JOIN sys_user reviewer ON reviewer.id = postmortem.reviewed_by
                """;
    }

    private static String followUpSelect() {
        return """
                SELECT follow_up.id, follow_up.postmortem_id, follow_up.title,
                       follow_up.description, follow_up.priority, follow_up.status,
                       follow_up.owner_id, owner.display_name AS owner_name,
                       follow_up.due_date, follow_up.created_by,
                       creator.display_name AS created_by_name, follow_up.completed_by,
                       completer.display_name AS completed_by_name, follow_up.completed_at,
                       follow_up.version, follow_up.created_at, follow_up.updated_at
                FROM postmortem_follow_up follow_up
                JOIN sys_user owner ON owner.id = follow_up.owner_id
                JOIN sys_user creator ON creator.id = follow_up.created_by
                LEFT JOIN sys_user completer ON completer.id = follow_up.completed_by
                """;
    }

    private static PostmortemRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new PostmortemRow(rs.getLong("id"), rs.getLong("incident_id"),
                rs.getString("incident_code"), rs.getString("incident_title"),
                rs.getString("severity"), rs.getString("status"), rs.getString("summary"),
                rs.getString("customer_impact"), rs.getString("root_cause"),
                rs.getString("contributing_factors"), rs.getString("lessons_learned"),
                rs.getString("timeline_snapshot_json"), rs.getString("evidence_refs_json"),
                rs.getLong("created_by"), rs.getString("created_by_name"),
                rs.getObject("submitted_by", Long.class), rs.getString("submitted_by_name"),
                rs.getObject("reviewed_by", Long.class), rs.getString("reviewed_by_name"),
                rs.getString("review_comment"), rs.getObject("submitted_at", LocalDateTime.class),
                rs.getObject("reviewed_at", LocalDateTime.class),
                rs.getObject("published_at", LocalDateTime.class), rs.getInt("version"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }

    private static FollowUpView mapFollowUp(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new FollowUpView(rs.getLong("id"), rs.getLong("postmortem_id"),
                rs.getString("title"), rs.getString("description"), rs.getString("priority"),
                rs.getString("status"), rs.getLong("owner_id"), rs.getString("owner_name"),
                rs.getObject("due_date", LocalDate.class), rs.getLong("created_by"),
                rs.getString("created_by_name"), rs.getObject("completed_by", Long.class),
                rs.getString("completed_by_name"), rs.getObject("completed_at", LocalDateTime.class),
                rs.getInt("version"), rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }

    public enum ReviewDecision {
        PUBLISH, REQUEST_CHANGES
    }

    public enum Priority {
        HIGH, MEDIUM, LOW
    }

    public record DraftContent(String summary, String customerImpact, String rootCause,
                               String contributingFactors, String lessonsLearned) {
    }

    public record FollowUpContent(String title, String description, Priority priority,
                                  long ownerId, LocalDate dueDate) {
    }

    public record PostmortemView(long id, long incidentId, String incidentCode,
                                 String incidentTitle, String severity, String status,
                                 String summary, String customerImpact, String rootCause,
                                 String contributingFactors, String lessonsLearned,
                                 JsonNode timelineSnapshot, JsonNode evidenceRefs,
                                 long createdById, String createdByName,
                                 Long submittedById, String submittedByName,
                                 Long reviewedById, String reviewedByName, String reviewComment,
                                 LocalDateTime submittedAt, LocalDateTime reviewedAt,
                                 LocalDateTime publishedAt, int version,
                                 LocalDateTime createdAt, LocalDateTime updatedAt,
                                 List<FollowUpView> followUps) {
    }

    public record FollowUpView(long id, long postmortemId, String title, String description,
                               String priority, String status, long ownerId, String ownerName,
                               LocalDate dueDate, long createdById, String createdByName,
                               Long completedById, String completedByName, LocalDateTime completedAt,
                               int version, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    private record IncidentContext(long id, String incidentCode, String title, String description,
                                   String severity, String status, long resourceId, String resourceName,
                                   LocalDateTime createdAt, LocalDateTime resolvedAt,
                                   int alertCount, int occurrenceCount) {
    }

    private record ReportContext(long id, String summary, String hypothesis) {
    }

    private record TimelineSnapshot(String eventType, String content, String evidenceRef,
                                    String actor, LocalDateTime createdAt) {
    }

    private record ChangeContext(String code, String summary, LocalDateTime startedAt) {
    }

    private record PostmortemRow(long id, long incidentId, String incidentCode,
                                 String incidentTitle, String severity, String status,
                                 String summary, String customerImpact, String rootCause,
                                 String contributingFactors, String lessonsLearned,
                                 String timelineSnapshotJson, String evidenceRefsJson,
                                 long createdById, String createdByName,
                                 Long submittedById, String submittedByName,
                                 Long reviewedById, String reviewedByName, String reviewComment,
                                 LocalDateTime submittedAt, LocalDateTime reviewedAt,
                                 LocalDateTime publishedAt, int version,
                                 LocalDateTime createdAt, LocalDateTime updatedAt) {
    }
}
