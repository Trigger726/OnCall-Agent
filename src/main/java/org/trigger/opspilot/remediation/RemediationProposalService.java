package org.trigger.opspilot.remediation;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.trigger.opspilot.audit.AuditService;
import org.trigger.opspilot.common.ApiException;
import org.trigger.opspilot.investigation.tool.InvestigationTool.ToolEvidence;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class RemediationProposalService {
    private static final BigDecimal PROPOSAL_CONFIDENCE_THRESHOLD = new BigDecimal("0.80");

    private final JdbcClient jdbcClient;
    private final AuditService auditService;

    public RemediationProposalService(JdbcClient jdbcClient, AuditService auditService) {
        this.jdbcClient = jdbcClient;
        this.auditService = auditService;
    }

    public ProposalView createForRun(long incidentId, long runId, Long requestedBy,
                                     String requesterIp, BigDecimal confidence,
                                     List<ToolEvidence> evidence) {
        if (requestedBy == null || confidence.compareTo(PROPOSAL_CONFIDENCE_THRESHOLD) < 0) return null;
        Optional<Long> changeId = evidence.stream().map(ToolEvidence::ref)
                .filter(ref -> ref != null && ref.startsWith("change:"))
                .map(RemediationProposalService::parseChangeId).flatMap(Optional::stream).findFirst();
        if (changeId.isEmpty()) return null;

        ChangeCandidate change = jdbcClient.sql("""
                        SELECT change_record.id, change_record.change_code, change_record.resource_id,
                               change_record.summary, resource.name AS resource_name
                        FROM change_record
                        JOIN cmdb_resource resource ON resource.id = change_record.resource_id
                        WHERE change_record.id = :changeId
                        """)
                .param("changeId", changeId.get())
                .query((rs, rowNum) -> new ChangeCandidate(
                        rs.getLong("id"), rs.getString("change_code"), rs.getLong("resource_id"),
                        rs.getString("summary"), rs.getString("resource_name")))
                .optional().orElse(null);
        if (change == null) return null;

        KeyHolder keyHolder = new GeneratedKeyHolder();
        String title = "回滚变更 " + change.changeCode();
        String description = "Agent 调查 #" + runId + " 建议对“" + change.summary()
                + "”执行受控回滚。批准只解除治理门禁，OpsPilot 不会自动修改生产环境。";
        String evidenceRef = "agent-run:" + runId + ",change:" + change.id();
        jdbcClient.sql("""
                        INSERT INTO remediation_proposal(
                          incident_id, run_id, change_id, target_resource_id, action_type,
                          risk_level, status, title, description, evidence_ref, requested_by)
                        VALUES (:incidentId, :runId, :changeId, :resourceId, 'ROLLBACK_CHANGE',
                          'HIGH', 'PENDING_APPROVAL', :title, :description, :evidenceRef, :requestedBy)
                        """)
                .param("incidentId", incidentId).param("runId", runId).param("changeId", change.id())
                .param("resourceId", change.resourceId()).param("title", title)
                .param("description", description).param("evidenceRef", evidenceRef)
                .param("requestedBy", requestedBy).update(keyHolder, "id");
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "REMEDIATION_PROPOSAL_CREATE_FAILED", "无法创建受控处置提案");
        }
        long proposalId = key.longValue();
        jdbcClient.sql("""
                        INSERT INTO incident_timeline(incident_id, event_type, actor_id, content, evidence_ref)
                        VALUES (:incidentId, 'ACTION_PROPOSED', :actorId, :content, :evidenceRef)
                        """)
                .param("incidentId", incidentId).param("actorId", requestedBy)
                .param("content", "Agent 生成高风险处置提案：" + title + "，等待独立审批")
                .param("evidenceRef", "remediation-proposal:" + proposalId).update();
        auditService.recordAs(requestedBy, requesterIp, "REMEDIATION_PROPOSED",
                "REMEDIATION_PROPOSAL", proposalId,
                "运行 #" + runId + " 基于 " + evidenceRef + " 生成高风险回滚提案");
        return get(proposalId);
    }

    public List<ProposalView> listByIncident(long incidentId) {
        return jdbcClient.sql(baseSelect() + " WHERE proposal.incident_id = :incidentId"
                        + " ORDER BY proposal.created_at DESC, proposal.id DESC")
                .param("incidentId", incidentId).query((rs, rowNum) -> map(rs)).list();
    }

    public ProposalView review(long proposalId, long reviewerId, Decision decision,
                               int version, String comment) {
        ProposalView current = get(proposalId);
        if (!"PENDING_APPROVAL".equals(current.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "REMEDIATION_ALREADY_REVIEWED", "该处置提案已完成审批");
        }
        if (current.requestedById() == reviewerId) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "ACTION_SELF_APPROVAL_FORBIDDEN", "高风险处置提案禁止发起人自批");
        }
        String targetStatus = decision == Decision.APPROVE ? "APPROVED" : "REJECTED";
        LocalDateTime reviewedAt = LocalDateTime.now();
        int updated = jdbcClient.sql("""
                        UPDATE remediation_proposal
                        SET status = :status, reviewed_by = :reviewerId, review_comment = :comment,
                            reviewed_at = :reviewedAt, updated_at = :reviewedAt, version = version + 1
                        WHERE id = :proposalId AND version = :version AND status = 'PENDING_APPROVAL'
                        """)
                .param("status", targetStatus).param("reviewerId", reviewerId)
                .param("comment", comment.trim()).param("reviewedAt", reviewedAt)
                .param("proposalId", proposalId).param("version", version).update();
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "REMEDIATION_VERSION_CONFLICT", "处置提案已被其他审批人更新，请刷新后重试");
        }
        String decisionText = decision == Decision.APPROVE ? "批准" : "拒绝";
        jdbcClient.sql("""
                        INSERT INTO incident_timeline(incident_id, event_type, actor_id, content, evidence_ref)
                        VALUES (:incidentId, 'ACTION_REVIEWED', :actorId, :content, :evidenceRef)
                        """)
                .param("incidentId", current.incidentId()).param("actorId", reviewerId)
                .param("content", decisionText + "高风险处置提案：" + current.title() + "；" + comment.trim())
                .param("evidenceRef", "remediation-proposal:" + proposalId).update();
        auditService.record("REMEDIATION_" + targetStatus, "REMEDIATION_PROPOSAL", proposalId,
                decisionText + "提案，版本 " + version + " -> " + (version + 1));
        return get(proposalId);
    }

    public ProposalView get(long proposalId) {
        return jdbcClient.sql(baseSelect() + " WHERE proposal.id = :proposalId")
                .param("proposalId", proposalId).query((rs, rowNum) -> map(rs)).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "REMEDIATION_PROPOSAL_NOT_FOUND", "处置提案不存在"));
    }

    private static String baseSelect() {
        return """
                SELECT proposal.id, proposal.incident_id, proposal.run_id, proposal.change_id,
                       change_record.change_code, proposal.target_resource_id,
                       resource.resource_code, resource.name AS resource_name,
                       proposal.action_type, proposal.risk_level, proposal.status,
                       proposal.title, proposal.description, proposal.evidence_ref,
                       proposal.requested_by, requester.display_name AS requested_by_name,
                       proposal.reviewed_by, reviewer.display_name AS reviewed_by_name,
                       proposal.review_comment, proposal.version, proposal.reviewed_at,
                       proposal.created_at, proposal.updated_at
                FROM remediation_proposal proposal
                LEFT JOIN change_record ON change_record.id = proposal.change_id
                JOIN cmdb_resource resource ON resource.id = proposal.target_resource_id
                JOIN sys_user requester ON requester.id = proposal.requested_by
                LEFT JOIN sys_user reviewer ON reviewer.id = proposal.reviewed_by
                """;
    }

    private static ProposalView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ProposalView(
                rs.getLong("id"), rs.getLong("incident_id"), rs.getLong("run_id"),
                rs.getObject("change_id", Long.class), rs.getString("change_code"),
                rs.getLong("target_resource_id"), rs.getString("resource_code"),
                rs.getString("resource_name"), rs.getString("action_type"),
                rs.getString("risk_level"), rs.getString("status"), rs.getString("title"),
                rs.getString("description"), rs.getString("evidence_ref"),
                rs.getLong("requested_by"), rs.getString("requested_by_name"),
                rs.getObject("reviewed_by", Long.class), rs.getString("reviewed_by_name"),
                rs.getString("review_comment"), rs.getInt("version"),
                rs.getObject("reviewed_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }

    private static Optional<Long> parseChangeId(String ref) {
        try {
            return Optional.of(Long.parseLong(ref.substring("change:".length())));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    public enum Decision {
        APPROVE, REJECT
    }

    private record ChangeCandidate(long id, String changeCode, long resourceId,
                                   String summary, String resourceName) {
    }

    public record ProposalView(long id, long incidentId, long runId, Long changeId,
                               String changeCode, long targetResourceId, String resourceCode,
                               String resourceName, String actionType, String riskLevel,
                               String status, String title, String description, String evidenceRef,
                               long requestedById, String requestedByName, Long reviewedById,
                               String reviewedByName, String reviewComment, int version,
                               LocalDateTime reviewedAt, LocalDateTime createdAt,
                               LocalDateTime updatedAt) {
    }
}
