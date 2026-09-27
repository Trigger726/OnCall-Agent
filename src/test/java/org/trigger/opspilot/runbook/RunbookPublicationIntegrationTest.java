package org.trigger.opspilot.runbook;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-publication;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=disabled", "opspilot.ai.enabled=false"
})
@Transactional
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class RunbookPublicationIntegrationTest {
    @Autowired RunbookService runbooks;
    @Autowired RunbookPublicationService publications;
    @Autowired JdbcClient jdbc;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;

    private String login(String name) throws Exception {
        String json = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/login")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(java.util.Map.of("username", name, "password", "OpsPilot@2026"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(json).path("data").path("accessToken").asText();
    }

    private org.springframework.test.web.servlet.ResultActions decide(long id, String token, int version,
            String key, String reason) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/runbooks/publications/" + id + "/decisions")
                .header("Authorization", token).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(java.util.Map.of("expectedVersion", version, "decision", "APPROVE",
                        "requestKey", key, "reason", reason))));
    }

    @Test void shouldEnforceHttpRolesIndependentReviewerAndCurrentDatabaseRole() throws Exception {
        String admin = login("admin"), manager = login("lina"), oncall = login("zhangwei");
        var draft = runbooks.importMarkdown(PublicationScenarios.command("publication-http-role", "http probe", java.util.List.of("ON_CALL")), 1L).document();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/runbooks/publications")
                .header("Authorization", oncall)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/runbooks/publications/" + draft.id())
                .header("Authorization", oncall)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        decide(draft.id(), admin, 0, PublicationScenarios.key(), "本人不能审批")
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code").value("RUNBOOK_SELF_REVIEW_FORBIDDEN"));
        jdbc.sql("UPDATE sys_user SET role_code='ON_CALL' WHERE id=3").update();
        decide(draft.id(), manager, 0, PublicationScenarios.key(), "旧令牌不放行")
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden())
                // JWT filter reloads the database role before @PreAuthorize; rejection precedes the service.
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code").value("ACCESS_DENIED"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/runbooks/publications")
                .header("Authorization", manager)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }

    @Test void shouldValidateAndFreezeHttpDecisionIdentityBeforeRedaction() throws Exception {
        String manager = login("lina");
        var draft = runbooks.importMarkdown(PublicationScenarios.command("publication-http-key", "key probe", java.util.List.of("ON_CALL")), 1L).document();
        decide(draft.id(), manager, 0, "invalid", "复核")
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        decide(draft.id(), manager, 1, PublicationScenarios.key(), "旧版本")
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        String key = PublicationScenarios.key();
        decide(draft.id(), manager, 0, key, "token=fictional-secret-one")
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.reviewNote").value("token=***"));
        decide(draft.id(), manager, 0, key, "token=fictional-secret-one")
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        decide(draft.id(), manager, 0, key, "token=fictional-secret-two")
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code").value("RUNBOOK_PUBLICATION_KEY_CONFLICT"));
        var other = runbooks.importMarkdown(PublicationScenarios.command("publication-other-key", "other probe", java.util.List.of("ON_CALL")), 1L).document();
        decide(other.id(), manager, 0, key, "复用键")
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        assertThat(publications.detail(other.id()).document().status()).isEqualTo("PENDING_REVIEW");
        assertThat(jdbc.sql("SELECT detail FROM audit_log WHERE action='RUNBOOK_PUBLICATION_APPROVE' AND target_id=:id")
                .param("id", Long.toString(draft.id())).query(String.class).single()).doesNotContain("fictional-secret");
    }

    @Test void shouldFilterQueueBeforeLimitAndExposeTruncationWithoutContent() throws Exception {
        var draft = runbooks.importMarkdown(PublicationScenarios.command("publication-queue", "private queue body", java.util.List.of("ON_CALL")), 1L).document();
        for (int i = 0; i < 201; i++) {
            jdbc.sql("""
                    INSERT INTO runbook_document(stable_key,version_no,status,resource_type,title,source_type,
                      source_name,content_hash,markdown_content,created_by,published_at)
                    VALUES (:key,1,'REJECTED','APPLICATION','new unrelated','MARKDOWN','fixture.md','fixture','hidden',1,NULL)
                    """).param("key", "publication-unrelated-" + i).update();
        }
        var queue = publications.list("PENDING_REVIEW");
        assertThat(queue.total()).isEqualTo(1);
        assertThat(queue.items()).extracting(RunbookPublicationService.QueueItem::id).containsExactly(draft.id());
        assertThat(publications.list("REJECTED").truncated()).isTrue();
        String response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/runbooks/publications")
                .header("Authorization", login("lina"))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("private queue body", "markdown", "decision_hash");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"APPROVE,false", "APPROVE,true", "REJECT,false", "REJECT,true", "WITHDRAW,false", "WITHDRAW,true"})
    void shouldRequireAnExplicitCapturedHttpReviewVersion(String decision, boolean explicitNull) throws Exception {
        var draft = runbooks.importMarkdown(PublicationScenarios.command("publication-required-version", "version probe", java.util.List.of("ON_CALL")), 1L).document();
        String token = login("WITHDRAW".equals(decision) ? "admin" : "lina");
        String key = PublicationScenarios.key();
        var body = mapper.createObjectNode().put("decision", decision).put("requestKey", key).put("reason", "明确核对候选版本");
        if (explicitNull) body.putNull("expectedVersion");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/runbooks/publications/" + draft.id() + "/decisions")
                .header("Authorization", token).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(body)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code").value("VALIDATION_ERROR"));
        var unchanged = publications.detail(draft.id());
        assertThat(unchanged.document().status()).isEqualTo("PENDING_REVIEW");
        assertThat(unchanged.reviewVersion()).isZero();
        assertThat(unchanged.currentPublishedVersion()).isZero();
        assertThat(unchanged.decision()).isNull();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action LIKE 'RUNBOOK_PUBLICATION_%' AND target_id=:id")
                .param("id", Long.toString(draft.id())).query(Long.class).single()).isZero();
        // Zero is a valid initial captured version, not a fallback for an absent JSON field.
        body.put("expectedVersion", 0);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/runbooks/publications/" + draft.id() + "/decisions")
                .header("Authorization", token).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(body)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.reviewVersion").value(1));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action LIKE 'RUNBOOK_PUBLICATION_%' AND target_id=:id")
                .param("id", Long.toString(draft.id())).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void shouldKeepUnapprovedVersionsOutOfConsoleAgentAndHistory() {
        PublicationScenarios.verifyLifecycle(runbooks, publications, jdbc);
    }

    @Test
    void shouldFencePublicationAgainstNewerApprovedBaselineAndReplayExactDecision() {
        PublicationScenarios.verifyBaseline(runbooks, publications, jdbc);
    }

    @Test
    void shouldDeduplicateOnlyIdenticalSubmissionAndCheckCurrentRole() {
        PublicationScenarios.verifyIdentity(runbooks, publications, jdbc);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM runbook_document WHERE status='PUBLISHED'")
                .query(Long.class).single()).isEqualTo(6);
    }
}
