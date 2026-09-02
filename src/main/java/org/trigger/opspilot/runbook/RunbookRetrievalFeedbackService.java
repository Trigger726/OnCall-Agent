package org.trigger.opspilot.runbook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.trigger.opspilot.audit.AuditService;
import org.trigger.opspilot.common.ApiException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class RunbookRetrievalFeedbackService {
    private static final Logger log = LoggerFactory.getLogger(RunbookRetrievalFeedbackService.class);
    private static final Set<String> REVIEW_DECISIONS = Set.of("APPROVE", "REJECT");

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public RunbookRetrievalFeedbackService(JdbcClient jdbcClient, ObjectMapper objectMapper,
                                           AuditService auditService) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    Long recordSearch(String query, String sourceType, String requestedMode, String actualEngine,
                      String roleCode, String semanticStatus, java.math.BigDecimal semanticCoverage,
                      int candidateChunkCount, int topK, long latencyMs,
                      List<RunbookService.SearchResult> results, Long actorId) {
        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcClient.sql("""
                            INSERT INTO runbook_retrieval_query(
                              query_text, query_hash, source_type, requested_mode, actual_engine, role_code,
                              semantic_status, semantic_coverage, candidate_chunk_count, top_k, latency_ms,
                              results_json, created_by)
                            VALUES (:query, :queryHash, :sourceType, :requestedMode, :actualEngine, :roleCode,
                              :semanticStatus, :semanticCoverage, :candidateCount, :topK, :latencyMs,
                              :results, :createdBy)
                            """)
                    .param("query", query).param("queryHash", sha256(query))
                    .param("sourceType", sourceType).param("requestedMode", requestedMode)
                    .param("actualEngine", actualEngine).param("roleCode", roleCode)
                    .param("semanticStatus", semanticStatus).param("semanticCoverage", semanticCoverage)
                    .param("candidateCount", candidateChunkCount).param("topK", topK)
                    .param("latencyMs", latencyMs).param("results", json(results))
                    .param("createdBy", actorId).update(keyHolder, "id");
            return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
        } catch (RuntimeException exception) {
            log.warn("Runbook retrieval telemetry persistence failed: {}", exception.getClass().getSimpleName());
            return null;
        }
    }

    @Transactional
    public JudgmentView submit(long searchId, String stableKey, int grade, String comment,
                               Long actorId, String actorRole) {
        if (actorId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "RUNBOOK_JUDGMENT_AUTH_REQUIRED", "需要登录后提交相关性判断");
        }
        if (grade < 0 || grade > 3) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RUNBOOK_RELEVANCE_GRADE_INVALID",
                    "relevanceGrade 必须在 0-3 之间");
        }
        String normalizedKey = normalizeStableKey(stableKey);
        RetrievalQuery query = queryById(searchId);
        if (!actorId.equals(query.createdBy())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "RUNBOOK_JUDGMENT_NOT_SEARCH_OWNER",
                    "只能评价本人执行的检索");
        }
        if (!containsStableKey(query.resultsJson(), normalizedKey)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RUNBOOK_JUDGMENT_RESULT_MISMATCH",
                    "只能评价该次检索实际返回的文档");
        }
        Long existingId = jdbcClient.sql("""
                        SELECT id FROM runbook_relevance_judgment
                        WHERE search_id = :searchId AND document_stable_key = :stableKey AND judged_by = :actorId
                        """).param("searchId", searchId).param("stableKey", normalizedKey).param("actorId", actorId)
                .query(Long.class).optional().orElse(null);
        long judgmentId;
        if (existingId == null) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            try {
                jdbcClient.sql("""
                                INSERT INTO runbook_relevance_judgment(
                                  search_id, document_stable_key, relevance_grade, comment, judged_by)
                                VALUES (:searchId, :stableKey, :grade, :comment, :actorId)
                                """).param("searchId", searchId).param("stableKey", normalizedKey)
                        .param("grade", grade).param("comment", optionalText(comment, 500))
                        .param("actorId", actorId).update(keyHolder, "id");
            } catch (DuplicateKeyException exception) {
                throw new ApiException(HttpStatus.CONFLICT, "RUNBOOK_JUDGMENT_CONFLICT", "相关性判断并发冲突，请重试");
            }
            if (keyHolder.getKey() == null) throw new IllegalStateException("Missing judgment id");
            judgmentId = keyHolder.getKey().longValue();
        } else {
            int updated = jdbcClient.sql("""
                            UPDATE runbook_relevance_judgment
                            SET relevance_grade = :grade, comment = :comment, version_no = version_no + 1
                            WHERE id = :id AND review_status = 'PENDING'
                            """).param("grade", grade).param("comment", optionalText(comment, 500))
                    .param("id", existingId).update();
            if (updated == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "RUNBOOK_JUDGMENT_ALREADY_REVIEWED",
                        "已复核的判断不能修改");
            }
            judgmentId = existingId;
        }
        auditService.record("RUNBOOK_RELEVANCE_JUDGE", "RUNBOOK_JUDGMENT", judgmentId,
                "检索 #" + searchId + "；文档 " + normalizedKey + "；等级 " + grade + "；角色 " + actorRole);
        return judgmentById(judgmentId, null);
    }

    public List<PendingJudgmentView> pending(Long reviewerId) {
        return jdbcClient.sql("""
                        SELECT j.id, j.search_id, q.query_text, q.source_type, q.actual_engine,
                               q.results_json, j.document_stable_key, j.version_no, j.created_at
                        FROM runbook_relevance_judgment j
                        JOIN runbook_retrieval_query q ON q.id = j.search_id
                        WHERE j.review_status = 'PENDING' AND j.judged_by <> :reviewerId
                        ORDER BY j.created_at, j.id
                        LIMIT 100
                        """).param("reviewerId", reviewerId)
                .query((rs, rowNum) -> {
                    SnapshotDocument document = snapshotDocument(
                            rs.getString("results_json"), rs.getString("document_stable_key"));
                    return new PendingJudgmentView(rs.getLong("id"), rs.getLong("search_id"),
                            rs.getString("query_text"), rs.getString("source_type"),
                            rs.getString("actual_engine"), rs.getString("document_stable_key"),
                            document.title(), document.excerpt(), document.citation(),
                            rs.getInt("version_no"), rs.getObject("created_at", LocalDateTime.class));
                }).list();
    }

    @Transactional
    public JudgmentView review(long judgmentId, int expectedVersion, String rawDecision,
                               Integer reviewerGrade, String note, Long reviewerId) {
        String decision = rawDecision == null ? "" : rawDecision.trim().toUpperCase(Locale.ROOT);
        if (!REVIEW_DECISIONS.contains(decision)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RUNBOOK_JUDGMENT_DECISION_INVALID",
                    "decision 仅支持 APPROVE 或 REJECT");
        }
        if ("APPROVE".equals(decision) && reviewerGrade == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RUNBOOK_REVIEWER_GRADE_REQUIRED",
                    "批准判断时必须提交 reviewerGrade");
        }
        if (reviewerGrade != null && (reviewerGrade < 0 || reviewerGrade > 3)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RUNBOOK_REVIEWER_GRADE_INVALID",
                    "reviewerGrade 必须在 0-3 之间");
        }
        JudgmentRow judgment = judgmentRow(judgmentId);
        if (reviewerId != null && reviewerId.equals(judgment.judgedBy())) {
            throw new ApiException(HttpStatus.CONFLICT, "RUNBOOK_JUDGMENT_SELF_REVIEW_FORBIDDEN",
                    "提交人不能复核自己的相关性判断");
        }
        String status = "APPROVE".equals(decision) ? "APPROVED" : "REJECTED";
        int updated = jdbcClient.sql("""
                        UPDATE runbook_relevance_judgment
                        SET review_status = :status, reviewed_by = :reviewerId,
                            reviewed_at = CURRENT_TIMESTAMP, reviewer_grade = :reviewerGrade,
                            review_note = :note, version_no = version_no + 1
                        WHERE id = :id AND version_no = :expectedVersion AND review_status = 'PENDING'
                        """).param("status", status).param("reviewerId", reviewerId)
                .param("reviewerGrade", reviewerGrade)
                .param("note", optionalText(note, 500)).param("id", judgmentId)
                .param("expectedVersion", expectedVersion).update();
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "RUNBOOK_JUDGMENT_VERSION_CONFLICT",
                    "相关性判断已被其他复核人更新，请刷新后重试");
        }
        String promotedCaseKey = null;
        if ("APPROVED".equals(status) && reviewerGrade >= 2) {
            promotedCaseKey = "human-judgment-" + judgmentId;
            jdbcClient.sql("""
                            INSERT INTO runbook_retrieval_eval_case(
                              case_key, query_text, expected_stable_key, enabled,
                              source_type, judgment_id, reviewed_by, relevance_grade)
                            VALUES (:caseKey, :query, :stableKey, TRUE, 'HUMAN_JUDGMENT',
                              :judgmentId, :reviewerId, :grade)
                            """).param("caseKey", promotedCaseKey).param("query", judgment.query())
                    .param("stableKey", judgment.stableKey()).param("judgmentId", judgmentId)
                    .param("reviewerId", reviewerId).param("grade", reviewerGrade).update();
        }
        auditService.record("RUNBOOK_RELEVANCE_REVIEW", "RUNBOOK_JUDGMENT", judgmentId,
                status + "；提交等级 " + judgment.relevanceGrade()
                        + (reviewerGrade == null ? "" : "；复核等级 " + reviewerGrade)
                        + (promotedCaseKey == null ? "；未进入正相关评测集" : "；评测 case " + promotedCaseKey));
        return judgmentById(judgmentId, promotedCaseKey);
    }

    public AgreementView agreement() {
        List<GradePair> pairs = jdbcClient.sql("""
                        SELECT relevance_grade, reviewer_grade
                        FROM runbook_relevance_judgment
                        WHERE review_status = 'APPROVED' AND reviewer_grade IS NOT NULL
                        ORDER BY id
                        """)
                .query((rs, rowNum) -> new GradePair(
                        rs.getInt("relevance_grade"), rs.getInt("reviewer_grade"))).list();
        return calculateAgreement(pairs);
    }

    private RetrievalQuery queryById(long id) {
        return jdbcClient.sql("""
                        SELECT id, query_text, results_json, created_by
                        FROM runbook_retrieval_query WHERE id = :id
                        """).param("id", id)
                .query((rs, rowNum) -> new RetrievalQuery(rs.getLong("id"), rs.getString("query_text"),
                        rs.getString("results_json"), rs.getObject("created_by", Long.class)))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "RUNBOOK_SEARCH_NOT_FOUND", "检索记录不存在"));
    }

    private JudgmentRow judgmentRow(long id) {
        return jdbcClient.sql("""
                        SELECT j.id, q.query_text, j.document_stable_key, j.relevance_grade, j.judged_by
                        FROM runbook_relevance_judgment j
                        JOIN runbook_retrieval_query q ON q.id = j.search_id
                        WHERE j.id = :id
                        """).param("id", id)
                .query((rs, rowNum) -> new JudgmentRow(rs.getLong("id"), rs.getString("query_text"),
                        rs.getString("document_stable_key"), rs.getInt("relevance_grade"),
                        rs.getLong("judged_by")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "RUNBOOK_JUDGMENT_NOT_FOUND", "相关性判断不存在"));
    }

    private JudgmentView judgmentById(long id, String promotedCaseKey) {
        return jdbcClient.sql("""
                        SELECT j.id, j.search_id, q.query_text, q.source_type, q.actual_engine,
                               j.document_stable_key, j.relevance_grade, j.comment, j.review_status,
                               j.version_no, j.judged_by, u.display_name AS judged_by_name, j.created_at,
                               j.reviewed_by, j.reviewed_at, j.reviewer_grade, j.review_note
                        FROM runbook_relevance_judgment j
                        JOIN runbook_retrieval_query q ON q.id = j.search_id
                        JOIN sys_user u ON u.id = j.judged_by
                        WHERE j.id = :id
                        """).param("id", id).query((rs, rowNum) -> mapJudgment(rs, promotedCaseKey)).single();
    }

    private JudgmentView mapJudgment(java.sql.ResultSet rs, String promotedCaseKey) throws java.sql.SQLException {
        return new JudgmentView(rs.getLong("id"), rs.getLong("search_id"), rs.getString("query_text"),
                rs.getString("source_type"), rs.getString("actual_engine"),
                rs.getString("document_stable_key"), rs.getInt("relevance_grade"), rs.getString("comment"),
                rs.getString("review_status"), rs.getInt("version_no"), rs.getLong("judged_by"),
                rs.getString("judged_by_name"), rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("reviewed_by", Long.class), rs.getObject("reviewed_at", LocalDateTime.class),
                rs.getObject("reviewer_grade", Integer.class), rs.getString("review_note"), promotedCaseKey);
    }

    private SnapshotDocument snapshotDocument(String resultsJson, String stableKey) {
        try {
            for (JsonNode result : objectMapper.readTree(resultsJson)) {
                if (stableKey.equals(result.path("stableKey").asText())) {
                    return new SnapshotDocument(result.path("title").asText(), result.path("excerpt").asText(),
                            result.path("citation").asText());
                }
            }
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "RUNBOOK_SEARCH_SNAPSHOT_DOCUMENT_MISSING", "检索快照缺少待复核文档");
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "RUNBOOK_SEARCH_SNAPSHOT_INVALID", "检索结果快照无法读取");
        }
    }

    static AgreementView calculateAgreement(List<GradePair> pairs) {
        int sampleCount = pairs.size();
        if (sampleCount == 0) {
            return new AgreementView(0, BigDecimal.ZERO.setScale(6), BigDecimal.ZERO.setScale(6),
                    null, "尚无完成双评分的批准样本");
        }
        int[][] confusion = new int[4][4];
        int[] submittedTotals = new int[4];
        int[] reviewerTotals = new int[4];
        int exact = 0;
        int withinOne = 0;
        for (GradePair pair : pairs) {
            confusion[pair.submittedGrade()][pair.reviewerGrade()]++;
            submittedTotals[pair.submittedGrade()]++;
            reviewerTotals[pair.reviewerGrade()]++;
            if (pair.submittedGrade() == pair.reviewerGrade()) exact++;
            if (Math.abs(pair.submittedGrade() - pair.reviewerGrade()) <= 1) withinOne++;
        }
        double observedDisagreement = 0;
        double expectedDisagreement = 0;
        for (int submitted = 0; submitted < 4; submitted++) {
            for (int reviewed = 0; reviewed < 4; reviewed++) {
                double weight = Math.abs(submitted - reviewed) / 3.0;
                observedDisagreement += weight * confusion[submitted][reviewed] / sampleCount;
                expectedDisagreement += weight
                        * ((double) submittedTotals[submitted] / sampleCount)
                        * ((double) reviewerTotals[reviewed] / sampleCount);
            }
        }
        BigDecimal kappa = expectedDisagreement == 0 ? null
                : decimal(1.0 - observedDisagreement / expectedDisagreement);
        String note = kappa == null
                ? "评分没有类别变化，线性加权 κ 未定义"
                : "线性加权 Cohen's kappa；仅统计已批准的双评分样本";
        return new AgreementView(sampleCount, ratio(exact, sampleCount), ratio(withinOne, sampleCount),
                kappa, note);
    }

    private static BigDecimal ratio(int numerator, int denominator) {
        return decimal((double) numerator / denominator);
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    private boolean containsStableKey(String resultsJson, String stableKey) {
        try {
            JsonNode results = objectMapper.readTree(resultsJson);
            for (JsonNode result : results) {
                if (stableKey.equals(result.path("stableKey").asText())) return true;
            }
            return false;
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "RUNBOOK_SEARCH_SNAPSHOT_INVALID", "检索结果快照无法读取");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize runbook retrieval snapshot", exception);
        }
    }

    private static String normalizeStableKey(String value) {
        if (value == null || value.isBlank() || value.length() > 80) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RUNBOOK_STABLE_KEY_INVALID", "stableKey 不合法");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String optionalText(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record JudgmentView(long id, long searchId, String query, String sourceType, String actualEngine,
                               String documentStableKey, int relevanceGrade, String comment,
                               String reviewStatus, int versionNo, long judgedBy, String judgedByName,
                               LocalDateTime createdAt, Long reviewedBy, LocalDateTime reviewedAt,
                               Integer reviewerGrade, String reviewNote, String promotedCaseKey) {
    }

    public record PendingJudgmentView(long id, long searchId, String query, String sourceType,
                                      String actualEngine, String documentStableKey, String documentTitle,
                                      String documentExcerpt, String citation, int versionNo,
                                      LocalDateTime createdAt) {
    }

    public record AgreementView(int sampleCount, BigDecimal exactAgreementRate,
                                BigDecimal withinOneAgreementRate, BigDecimal linearWeightedKappa,
                                String note) {
    }

    private record RetrievalQuery(long id, String query, String resultsJson, Long createdBy) {
    }

    private record JudgmentRow(long id, String query, String stableKey, int relevanceGrade, long judgedBy) {
    }

    record GradePair(int submittedGrade, int reviewerGrade) {
    }

    private record SnapshotDocument(String title, String excerpt, String citation) {
    }
}
