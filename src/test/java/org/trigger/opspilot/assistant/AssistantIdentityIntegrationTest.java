package org.trigger.opspilot.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;
import org.trigger.opspilot.investigation.AgentRunQueryService;
import org.trigger.opspilot.runbook.RunbookService;
import org.trigger.opspilot.security.SessionAuthorization;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:assistant-identity;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "management.server.port=0", "spring.ai.dashscope.api-key=disabled", "opspilot.ai.enabled=false",
        "opspilot.runbook.semantic.enabled=false", "opspilot.agent.recovery.enabled=false",
        "opspilot.oncall.rotation.enabled=false", "opspilot.oncall.escalation.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AssistantIdentityIntegrationTest {
    @Autowired AssistantService assistant;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired AgentRunQueryService agentRuns;
    @Autowired SessionAuthorization authorization;
    @Autowired TransactionTemplate transactions;
    @Autowired AssistantRequestStore requests;
    @Autowired RunbookService runbooks;
    @Autowired AssistantObservationService observations;
    long owner;

    @BeforeEach void owner() {
        String name = "identity_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.sql("INSERT INTO sys_user(username,password_hash,display_name,role_code,status) SELECT :name,password_hash,'Identity QA','ON_CALL','ACTIVE' FROM sys_user WHERE username='admin'")
                .param("name", name).update();
        owner = jdbc.sql("SELECT id FROM sys_user WHERE username=:name").param("name", name).query(Long.class).single();
    }

    @Test void bothUnboundAndIncidentBoundIdentityMustPersistWithoutIrrelevantEvidence() {
        for (Long incident : new Long[]{null, 1L}) {
            long session = assistant.createSession(owner, incident, null).session().id();
            for (String question : List.of("你是谁", "你是什么模型")) {
                var answer = assistant.sendMessage(session, owner, question);
                assertIdentity(answer);
                assertThat(assistant.getSession(session, owner).messages()).contains(answer);
            }
        }
        assertThat(searches()).isZero();
    }

    @Test void nativeStreamWithModelConfiguredMustStillUseDirectIdentityPath() {
        var ai = mock(AssistantAiService.class);
        var service = new AssistantService(jdbc, json, Optional.of(ai), agentRuns, authorization, transactions,
                requests, runbooks, observations, new AssistantIdentity(Optional.of(ai), Optional.empty()));
        long session = assistant.createSession(owner, 1L, null).session().id();
        var answer = service.streamMessage(session, owner, "你是什么模型", authorization.capture(owner), () -> {}, null,
                chunk -> { throw new AssertionError("Identity should complete directly, not masquerade as model deltas"); });
        assertThat(answer.content()).contains("通义 DashScope", "不会猜测型号", "没有调用模型");
        assertThat(answer.evidenceJson()).isEqualTo("[]");
        verifyNoInteractions(ai);
        assertThat(searches()).isZero();
    }

    @Test void sameKeyMustReplayExactlyOnePersistedAnswer() {
        long session = assistant.createSession(owner, null, null).session().id();
        String key = AssistantRequestStore.keyHash(UUID.randomUUID().toString());
        var lease = authorization.capture(owner);
        var identity = new AssistantRequestStore.Identity(key, UUID.randomUUID().toString(), System.currentTimeMillis() + 30000);
        var original = assistant.sendMessage(session, owner, "你是谁", lease, () -> {}, identity);
        var replay = assistant.sendMessage(session, owner, "你是谁", lease, () -> {},
                new AssistantRequestStore.Identity(key, UUID.randomUUID().toString(), System.currentTimeMillis() + 30000));
        assertIdentity(original);
        assertThat(replay).isEqualTo(original);
        assertThat(assistant.getSession(session, owner).messages()).hasSize(2);
        assertThat(searches()).isZero();
    }

    @Test void revokedActorMustNotCommitIdentityAnswer() {
        long session = assistant.createSession(owner, null, null).session().id();
        var lease = authorization.capture(owner);
        var revoked = new AtomicBoolean();
        assertThatThrownBy(() -> assistant.sendMessage(session, owner, "你是谁", lease, () -> {
            long messages = jdbc.sql("SELECT COUNT(*) FROM assistant_message WHERE session_id=:id").param("id", session).query(Long.class).single();
            if (messages > 0 && revoked.compareAndSet(false, true)) {
                jdbc.sql("UPDATE sys_user SET auth_version=auth_version+1 WHERE id=:id").param("id", owner).update();
            }
        })).isInstanceOf(CredentialsExpiredException.class);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM assistant_message WHERE session_id=:id AND role='ASSISTANT'").param("id", session).query(Long.class).single()).isZero();
        assertThat(searches()).isZero();
    }

    @Test void operationalQuestionAfterIdentityStillRetrievesPublishedRunbooks() {
        long session = assistant.createSession(owner, null, null).session().id();
        assertIdentity(assistant.sendMessage(session, owner, "你是谁"));
        var answer = assistant.sendMessage(session, owner, "Redis 连接池 active pending 慢命令");
        assertThat(answer.content()).contains("Runbook 检索依据", "原文片段");
        assertThat(answer.evidenceJson()).contains("RUNBOOK");
        assertThat(searches()).isEqualTo(1);
    }

    private long searches() {
        return jdbc.sql("SELECT COUNT(*) FROM runbook_retrieval_query WHERE created_by=:id").param("id", owner).query(Long.class).single();
    }
    private void assertIdentity(AssistantService.MessageView answer) {
        assertThat(answer.content()).contains("OpsPilot OnCall 助手", "未启用大语言模型")
                .doesNotContain("当前活跃 Incident", "Runbook 检索依据", "Incident 概况");
        assertThat(answer.evidenceJson()).isEqualTo("[]");
    }
}
