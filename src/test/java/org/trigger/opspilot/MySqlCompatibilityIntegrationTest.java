package org.trigger.opspilot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.trigger.opspilot.investigation.AgentRunEventService;
import org.trigger.opspilot.investigation.AgentRunQueryService;
import org.trigger.opspilot.investigation.InvestigationService;
import org.trigger.opspilot.postmortem.FollowUpEscalationService;
import org.trigger.opspilot.postmortem.PostmortemService;
import org.trigger.opspilot.problem.ProblemService;
import org.trigger.opspilot.problem.ProblemStatus;
import org.trigger.opspilot.runbook.RunbookRetrievalFeedbackService;
import org.trigger.opspilot.runbook.RunbookService;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "opspilot.mysql.it.enabled", matches = "true")
@Testcontainers
@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.h2.console.enabled=false",
        "spring.ai.dashscope.api-key=disabled",
        "opspilot.ai.enabled=false"
})
class MySqlCompatibilityIntegrationTest {
    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("opspilot_test")
            .withUsername("opspilot")
            .withPassword("opspilot-test")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private InvestigationService investigationService;

    @Autowired
    private AgentRunEventService eventService;

    @Autowired
    private RunbookService runbookService;

    @Autowired
    private RunbookRetrievalFeedbackService feedbackService;

    @Autowired
    private PostmortemService postmortemService;

    @Autowired
    private FollowUpEscalationService followUpEscalationService;

    @Autowired
    private ProblemService problemService;

    @Test
    void shouldApplyAllMigrationsAndRunIdempotentInvestigationOnMySql() throws SQLException {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        }
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM flyway_schema_history
                        WHERE version = '15' AND success = 1
                        """).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT title FROM incident WHERE id = 1")
                .query(String.class).single()).isEqualTo("统一结算接口持续超时");
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM information_schema.statistics
                        WHERE table_schema = DATABASE()
                          AND table_name = 'agent_investigation_run'
                          AND index_name = 'uq_agent_run_idempotency'
                          AND non_unique = 0
                        """).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM runbook_document WHERE status = 'PUBLISHED'")
                .query(Integer.class).single()).isEqualTo(6);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM runbook_chunk_embedding")
                .query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM runbook_retrieval_query")
                .query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM runbook_relevance_judgment WHERE reviewer_grade IS NOT NULL")
                .query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM runbook_retrieval_query WHERE snapshot_status = 'PURGED'")
                .query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM incident_postmortem")
                .query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM postmortem_follow_up")
                .query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM postmortem_follow_up_escalation")
                .query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM problem_record")
                .query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM problem_incident_link")
                .query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM runbook_retrieval_eval_case WHERE source_type = 'SEED'")
                .query(Integer.class).single()).isEqualTo(13);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM runbook_retrieval_eval_case " +
                        "WHERE relevance_grade = 3")
                .query(Integer.class).single()).isEqualTo(13);
        assertThat(runbookService.search("Redis 连接池 pending", "ON_CALL", 3).results().get(0).citation())
                .isEqualTo("runbook:legacy-runbook-2:v1#chunk-0");

        jdbcClient.sql("""
                        INSERT INTO runbook_retrieval_query(
                          query_text, query_hash, source_type, requested_mode, actual_engine, role_code,
                          semantic_status, semantic_coverage, candidate_chunk_count, top_k, latency_ms,
                          results_json, created_by, redacted_fields, created_at)
                        VALUES ('expired mysql snapshot', :queryHash, 'CONSOLE', 'BM25', 'BM25_LOCAL_V1',
                          'ON_CALL', 'NOT_REQUESTED', 0, 1, 3, 5, '[]', 2, 0, :createdAt)
                        """).param("queryHash", "a".repeat(64))
                .param("createdAt", LocalDateTime.now().minusDays(31)).update();
        RunbookRetrievalFeedbackService.RetentionCleanupResult cleanup = feedbackService.purgeExpired(null);
        assertThat(cleanup.purgedSnapshots()).isEqualTo(1);
        assertThat(feedbackService.purgeExpired(null).purgedSnapshots()).isZero();
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM runbook_retrieval_query
                        WHERE snapshot_status = 'PURGED' AND query_text = '[PURGED]'
                          AND results_json = '[]' AND created_by IS NULL AND purged_at IS NOT NULL
                        """).query(Integer.class).single()).isEqualTo(1);

        InvestigationService.RunActor actor = new InvestigationService.RunActor(1L, "127.0.0.1");
        String idempotencyKey = "mysql-it-" + UUID.randomUUID();
        InvestigationService.PreparedRun prepared = investigationService.prepare(
                1, "MYSQL_TESTCONTAINERS", actor, idempotencyKey, Duration.ofSeconds(30));
        InvestigationService.PreparedRun replay = investigationService.prepare(
                1, "MYSQL_TESTCONTAINERS_RETRY", actor, idempotencyKey, Duration.ofSeconds(30));

        assertThat(prepared.reused()).isFalse();
        assertThat(replay.reused()).isTrue();
        assertThat(replay.runId()).isEqualTo(prepared.runId());
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM agent_investigation_run
                        WHERE incident_id = 1 AND idempotency_key = :idempotencyKey
                        """).param("idempotencyKey", idempotencyKey)
                .query(Integer.class).single()).isEqualTo(1);

        InvestigationService.InvestigationResult result = investigationService.execute(
                prepared, actor, AgentRunEventService.EventSink.NOOP);
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.reportId()).isNotNull();

        AgentRunQueryService.AgentRunView run = investigationService.listRuns(1).stream()
                .filter(item -> item.id().equals(prepared.runId())).findFirst().orElseThrow();
        assertThat(run.steps()).hasSize(9);
        assertThat(run.steps().stream().filter(step -> "runbook_retrieval".equals(step.toolName()))
                .findFirst().orElseThrow().inputJson()).contains("BM25_LOCAL_V1", "runbook:legacy-runbook-");
        List<AgentRunEventService.EventView> events = eventService.list(prepared.runId(), 0);
        assertThat(events).hasSize(18);
        assertThat(events.get(0).eventType()).isEqualTo("RUN_QUEUED");
        assertThat(events.get(events.size() - 1).eventType()).isEqualTo("RUN_COMPLETED");

        PostmortemService.PostmortemView draft = postmortemService.createDraft(2, 3);
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(draft.timelineSnapshot()).hasSize(3);
        PostmortemService.PostmortemView edited = postmortemService.update(draft.id(), 0,
                new PostmortemService.DraftContent(
                        "认证服务发布后旧客户端 token 刷新失败",
                        "部分旧客户端会话续期失败，没有数据丢失",
                        "旧 token 格式的兼容分支缺少发布前契约回归",
                        "灰度指标没有按客户端版本拆分",
                        "补充三个客户端大版本的兼容矩阵并保留回滚开关"));
        assertThat(edited.version()).isEqualTo(1);
        PostmortemService.PostmortemView withFollowUp = postmortemService.addFollowUp(
                draft.id(), 1, 3,
                new PostmortemService.FollowUpContent(
                        "补齐旧客户端兼容回归", "覆盖三个客户端大版本并接入发布门禁",
                        PostmortemService.Priority.HIGH, 2, LocalDate.now().plusDays(7)));
        assertThat(withFollowUp.version()).isEqualTo(2);
        long followUpId = withFollowUp.followUps().get(0).id();
        PostmortemService.PostmortemView submitted = postmortemService.submit(draft.id(), 2, 3);
        assertThat(submitted.status()).isEqualTo("IN_REVIEW");
        PostmortemService.PostmortemView published = postmortemService.review(
                draft.id(), 3, 1, PostmortemService.ReviewDecision.PUBLISH,
                "证据和行动项完整，同意发布");
        assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThat(published.version()).isEqualTo(4);
        jdbcClient.sql("UPDATE postmortem_follow_up SET due_date = :dueDate WHERE id = :id")
                .param("dueDate", LocalDate.now().minusDays(1)).param("id", followUpId).update();
        FollowUpEscalationService.EscalationScanResult firstScan = followUpEscalationService.scan(
                LocalDate.now(), 1L, "mysql-testcontainers");
        FollowUpEscalationService.EscalationScanResult repeatedScan = followUpEscalationService.scan(
                LocalDate.now(), 1L, "mysql-testcontainers");
        assertThat(firstScan.createdEscalations()).isEqualTo(1);
        assertThat(repeatedScan.createdEscalations()).isZero();
        PostmortemService.PostmortemView completed = postmortemService.completeFollowUp(
                followUpId, 0, 2, "ON_CALL");
        assertThat(completed.followUps().get(0).status()).isEqualTo("DONE");
        assertThat(jdbcClient.sql("""
                        SELECT status FROM postmortem_follow_up_escalation WHERE follow_up_id = :id
                        """).param("id", followUpId).query(String.class).single())
                .isEqualTo("RESOLVED");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM audit_log WHERE target_type LIKE '%POSTMORTEM%'")
                .query(Integer.class).single()).isEqualTo(8);

        String recurrenceFingerprint = "c".repeat(64);
        LocalDateTime now = LocalDateTime.now();
        jdbcClient.sql("""
                        INSERT INTO incident(
                          id, incident_code, title, severity, status, service_resource_id,
                          acknowledged_at, resolved_at, created_at, updated_at)
                        VALUES
                          (301, 'INC-MYSQL-PROBLEM-301', '结算连接池首次耗尽', 'P1', 'RESOLVED', 1,
                           :firstAck, :firstResolved, :firstCreated, :firstResolved),
                          (302, 'INC-MYSQL-PROBLEM-302', '结算连接池再次耗尽', 'P1', 'RESOLVED', 1,
                           :secondAck, :secondResolved, :secondCreated, :secondResolved)
                        """).param("firstCreated", now.minusDays(7))
                .param("firstAck", now.minusDays(7).plusMinutes(3))
                .param("firstResolved", now.minusDays(7).plusMinutes(40))
                .param("secondCreated", now.minusDays(2))
                .param("secondAck", now.minusDays(2).plusMinutes(4))
                .param("secondResolved", now.minusDays(2).plusMinutes(35)).update();
        jdbcClient.sql("""
                        INSERT INTO alert_event(
                          source, external_event_id, fingerprint, service_resource_id,
                          severity, status, title, description, labels_json,
                          first_occurred_at, last_occurred_at, occurrence_count, incident_id)
                        VALUES
                          ('prometheus', 'mysql-problem-301', :fingerprint, 1, 'P1', 'RESOLVED',
                           '连接池耗尽', '首次', '{}', :firstAt, :firstAt, 8, 301),
                          ('prometheus', 'mysql-problem-302', :fingerprint, 1, 'P1', 'RESOLVED',
                           '连接池耗尽', '再次', '{}', :secondAt, :secondAt, 5, 302)
                        """).param("fingerprint", recurrenceFingerprint)
                .param("firstAt", now.minusDays(7)).param("secondAt", now.minusDays(2)).update();
        assertThat(problemService.recurrenceCandidates(
                LocalDate.now().minusDays(10), LocalDate.now(), 1L, 1, 20).total()).isEqualTo(1);
        ProblemService.ProblemCreateResult problemCreated = problemService.create(
                "1:" + recurrenceFingerprint, LocalDate.now().minusDays(10), LocalDate.now(),
                1L, "mysql-testcontainers");
        assertThat(problemCreated.created()).isTrue();
        assertThat(problemCreated.newlyLinkedIncidents()).isEqualTo(2);
        ProblemService.ProblemView knownError = problemService.update(
                problemCreated.problem().id(), 0,
                new ProblemService.ProblemUpdate(null, ProblemStatus.KNOWN_ERROR, null,
                        "连接池上限低于峰值并发", "临时扩容并限流", null),
                1L, "mysql-testcontainers");
        assertThat(knownError.status()).isEqualTo("KNOWN_ERROR");
        ProblemService.ProblemView problemResolved = problemService.update(
                knownError.id(), 1,
                new ProblemService.ProblemUpdate(null, ProblemStatus.RESOLVED, null,
                        null, null, "完成容量模型与自适应连接池配置"),
                1L, "mysql-testcontainers");
        assertThat(problemResolved.status()).isEqualTo("RESOLVED");
        assertThat(problemResolved.incidentCount()).isEqualTo(2);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM problem_incident_link")
                .query(Integer.class).single()).isEqualTo(2);
    }
}
