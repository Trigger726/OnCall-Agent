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
import org.trigger.opspilot.runbook.RunbookService;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
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

    @Test
    void shouldApplyAllMigrationsAndRunIdempotentInvestigationOnMySql() throws SQLException {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        }
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM flyway_schema_history
                        WHERE version = '11' AND success = 1
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
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM runbook_retrieval_eval_case WHERE source_type = 'SEED'")
                .query(Integer.class).single()).isEqualTo(13);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM runbook_retrieval_eval_case " +
                        "WHERE relevance_grade = 3")
                .query(Integer.class).single()).isEqualTo(13);
        assertThat(runbookService.search("Redis 连接池 pending", "ON_CALL", 3).results().get(0).citation())
                .isEqualTo("runbook:legacy-runbook-2:v1#chunk-0");

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
    }
}
