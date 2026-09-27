package org.trigger.opspilot.runbook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-runbook-trend;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=disabled", "opspilot.ai.enabled=false"
})
@AutoConfigureMockMvc
@Transactional
class RunbookRetrievalTrendIntegrationTest {
    @Autowired JdbcClient jdbc;
    @Autowired RunbookRetrievalTrendService service;
    @Autowired RunbookService runbooks;
    @Autowired RunbookRetrievalFeedbackService feedback;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void shouldSeparateCompletePartialPurgedEmptyAndSourceEngineKCohorts() {
        RetrievalTrendScenarios.verify(jdbc, service);
    }

    @Test
    void shouldPersistDistinctDocumentsNotChunkCountAndNotTrackOfflineSearch() {
        var response = runbooks.searchTracked("消息积压 consumer group offset 位点", "ADMIN", 1L, 5, "BM25", "CONSOLE");
        long distinct = response.results().stream().map(RunbookService.SearchResult::stableKey).distinct().count();
        assertThat(response.searchId()).isNotNull();
        assertThat(jdbc.sql("SELECT returned_document_count FROM runbook_retrieval_query WHERE id = :id")
                .param("id", response.searchId()).query(Integer.class).single()).isEqualTo((int) distinct);
        runbooks.search("消息积压", "ADMIN", 5, "BM25");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM runbook_retrieval_query").query(Long.class).single()).isEqualTo(1);
        var empty = runbooks.searchTracked("zzzznotermmatchunique", "ADMIN", 1L, 5, "BM25", "CONSOLE");
        assertThat(empty.results()).isEmpty();
        assertThat(jdbc.sql("SELECT returned_document_count FROM runbook_retrieval_query WHERE id = :id")
                .param("id", empty.searchId()).query(Integer.class).single()).isZero();
    }

    @Test
    void shouldRestrictAggregateEndpointAndExposeNoQueryOrActorText() throws Exception {
        RetrievalTrendScenarios.verify(jdbc, service);
        String path = "/api/v1/runbooks/searches/trend";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        for (String user : new String[]{"zhangwei", "auditor"}) {
            mvc.perform(get(path).header("Authorization", "Bearer " + login(user))).andExpect(status().isForbidden());
        }
        for (String user : new String[]{"admin", "lina"}) {
            var response = mvc.perform(get(path).header("Authorization", "Bearer " + login(user)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var data = mapper.readTree(response).path("data");
            assertThat(data.path("days").size()).isEqualTo(30);
            assertThat(data.path("topK").asInt()).isEqualTo(5);
            assertThat(data.path("totals").path("reviewedHitRateAtK").isNull()).isTrue();
            assertThat(response).doesNotContain("query_text", "results_json", "created_by", "TREND_FIXTURE_DO_NOT_EXPOSE");
            var historical = mvc.perform(get(path).header("Authorization", "Bearer " + login(user))
                    .queryParam("from", "1999-03-01").queryParam("to", "1999-03-02").queryParam("topK", "3"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(mapper.readTree(historical).path("data").path("totals").path("queryCount").asLong()).isEqualTo(7);
            assertThat(historical).doesNotContain("TREND_FIXTURE_DO_NOT_EXPOSE", "documentStableKey", "judgedBy");
        }
        mvc.perform(get(path).header("Authorization", "Bearer " + login("admin"))
                .queryParam("from", "1999-03-02").queryParam("to", "1999-03-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldKeepQualityCardinalityAfterRealRetentionAndAssignLateReviewsToQueryDate() {
        var search = runbooks.searchTracked("消息积压 consumer group offset 位点", "ADMIN", 1L, 5, "BM25", "CONSOLE");
        for (String doc : search.results().stream().map(RunbookService.SearchResult::stableKey).distinct().toList()) {
            var judgment = feedback.submit(search.searchId(), doc, 0, "test", 1L, "ADMIN");
            feedback.review(judgment.id(), judgment.versionNo(), "APPROVE", 2, "independent", 3L);
        }
        var day = java.time.LocalDate.of(1999, 4, 1);
        jdbc.sql("UPDATE runbook_retrieval_query SET created_at = :at WHERE id = :id")
                .param("at", day.atStartOfDay()).param("id", search.searchId()).update();
        var before = service.trend(day, day, "CONSOLE", "BM25_LOCAL_V1", 5).totals();
        assertThat(before.fullyReviewedQueries()).isEqualTo(1);
        assertThat(before.relevantQueries()).isEqualTo(1);
        assertThat(feedback.purgeExpired(null).purgedSnapshots()).isEqualTo(1);
        var after = service.trend(day, day, "CONSOLE", "BM25_LOCAL_V1", 5).totals();
        assertThat(after.reviewedHitRateAtK()).isEqualByComparingTo("1.000000");
        assertThat(after.fullyReviewedQueries()).isEqualTo(before.fullyReviewedQueries());
        assertThat(after.returnedQueries()).isEqualTo(before.returnedQueries());
        assertThat(after.purgedQueries()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT results_json FROM runbook_retrieval_query WHERE id = :id")
                .param("id", search.searchId()).query(String.class).single()).isEqualTo("[]");
    }

    private String login(String username) throws Exception {
        var response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"OpsPilot@2026\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("data").path("accessToken").asText();
    }
}
