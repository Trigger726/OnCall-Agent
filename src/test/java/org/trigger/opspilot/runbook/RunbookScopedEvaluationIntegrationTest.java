package org.trigger.opspilot.runbook;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.trigger.opspilot.common.ApiException;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-scoped-eval;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.ai.dashscope.api-key=disabled", "opspilot.ai.enabled=false", "opspilot.runbook.semantic.enabled=false"
})
@Transactional
class RunbookScopedEvaluationIntegrationTest {
    @Autowired RunbookService runbooks;
    @Autowired RunbookRetrievalFeedbackService feedback;
    @Autowired JdbcClient jdbc;

    @Test void shouldNotMergeIdenticalQuestionsFromDifferentRetrievalScopes() {
        jdbc.sql("UPDATE runbook_retrieval_eval_case SET enabled=FALSE").update();
        long owner=user("zhangwei"),reviewer=user("lina");
        String question="推荐当前 Incident 的排查手册";
        var redis=new RunbookService.SearchScope(List.of("MID-REDIS-01"),List.of("MIDDLEWARE","SERVICE"));
        var auth=new RunbookService.SearchScope(List.of("APP-AUTH"),List.of("APPLICATION","SERVICE"));
        approve(question,redis,"legacy-runbook-2",owner,reviewer);
        approve(question,auth,"legacy-runbook-3",owner,reviewer);
        var result=runbooks.evaluate(reviewer);
        assertThat(result.caseCount()).isEqualTo(2);
        assertThat(result.judgmentCount()).isEqualTo(2);
        assertThat(result.recallAt3()).isEqualByComparingTo("1");
        assertThat(runbooks.evaluate(reviewer).datasetVersion()).isEqualTo(result.datasetVersion());
        assertThat(jdbc.sql("SELECT COUNT(DISTINCT query_hash) FROM runbook_retrieval_query WHERE query_text=:q")
                .param("q",question).query(Integer.class).single()).isEqualTo(2);
    }

    @Test void shouldMergeCanonicalEquivalentScopesButSeparateIncidentIdentities() {
        jdbc.sql("UPDATE runbook_retrieval_eval_case SET enabled=FALSE").update();
        long owner=user("zhangwei"), reviewer=user("lina");
        String q="Redis 连接池 pending";
        var a=new RunbookService.SearchScope(List.of("MID-REDIS-01","APP-AUTH"),List.of("SERVICE","MIDDLEWARE"),1L,"INC-1","事件一");
        var reordered=new RunbookService.SearchScope(List.of("APP-AUTH","MID-REDIS-01","APP-AUTH"),List.of("MIDDLEWARE","SERVICE"),1L,"INC-1","事件一");
        approve(q,a,"legacy-runbook-2",owner,reviewer);
        approve(q,reordered,"legacy-runbook-2",owner,reviewer);
        var first=runbooks.evaluate(reviewer);
        assertThat(first.caseCount()).isEqualTo(1);
        approve(q,new RunbookService.SearchScope(a.serviceCodes(),a.resourceTypes(),2L,"INC-2","事件二"),"legacy-runbook-2",owner,reviewer);
        var second=runbooks.evaluate(reviewer);
        assertThat(second.caseCount()).isEqualTo(2);
        assertThat(second.judgmentCount()).isEqualTo(2); // Equivalent scope/document judgments are averaged into one qrel.
        assertThat(jdbc.sql("SELECT COUNT(*) FROM runbook_retrieval_eval_case WHERE enabled=TRUE").query(Integer.class).single()).isEqualTo(3);
        assertThat(second.datasetVersion()).isNotEqualTo(first.datasetVersion());
    }

    @Test void shouldReplayOriginalScopeAndReportOutOfScopeLabelWithoutGlobalFallback() {
        jdbc.sql("UPDATE runbook_retrieval_eval_case SET enabled=FALSE").update();
        long owner=user("zhangwei"),reviewer=user("lina");
        var scope=new RunbookService.SearchScope(List.of("MID-REDIS-01"),List.of("MIDDLEWARE"));
        long id=approve("Redis 连接池 pending",scope,"legacy-runbook-2",owner,reviewer);
        String saved=jdbc.sql("SELECT scope_json FROM runbook_retrieval_eval_case WHERE judgment_id=:id").param("id",id).query(String.class).single();
        jdbc.sql("DELETE FROM cmdb_relation WHERE source_resource_id=1 OR target_resource_id=1").update();
        assertThat(runbooks.evaluate(reviewer).recallAt3()).isEqualByComparingTo("1");
        jdbc.sql("UPDATE runbook_retrieval_eval_case SET expected_stable_key='legacy-runbook-3' WHERE judgment_id=:id").param("id",id).update();
        var failure=runbooks.evaluate(reviewer);
        assertThat(failure.recallAt3()).isZero();
        assertThat(failure.failuresJson()).contains("legacy-runbook-2","MID-REDIS-01").doesNotContain("\"returnedStableKeys\":[\"legacy-runbook-3\"]");
        assertThat(jdbc.sql("SELECT scope_json FROM runbook_retrieval_eval_case WHERE judgment_id=:id").param("id",id).query(String.class).single()).isEqualTo(saved);
    }

    @Test void shouldReviewUnknownScopeWithoutPromotingAndExplicitlyExcludeHistoricalLabels() {
        long owner=user("zhangwei"),reviewer=user("lina");
        var search=runbooks.searchTracked("Redis pending","ON_CALL",owner,3,"BM25","ASSISTANT");
        jdbc.sql("UPDATE runbook_retrieval_query SET scope_type='UNKNOWN' WHERE id=:id").param("id",search.searchId()).update();
        var judgment=feedback.submit(search.searchId(),"legacy-runbook-2",3,null,owner,"ON_CALL");
        var reviewed=feedback.review(judgment.id(),0,"APPROVE",3,null,reviewer);
        assertThat(reviewed.reviewStatus()).isEqualTo("APPROVED");
        assertThat(reviewed.promotedCaseKey()).isNull();
        jdbc.sql("INSERT INTO runbook_retrieval_eval_case(case_key,query_text,expected_stable_key,scope_type) VALUES('unknown-case','unknown-context','legacy-runbook-2','UNKNOWN')").update();
        var evaluation=runbooks.evaluate(reviewer);
        assertThat(evaluation.caseCount()).isEqualTo(13);
        assertThat(evaluation.evaluationNote()).contains("排除范围未知的 enabled qrel 1 条");
    }

    @Test void shouldRejectMalformedScopeBeforeReviewOrEvaluationWrites() {
        long owner=user("zhangwei"),reviewer=user("lina");
        var search=runbooks.searchTracked("Redis pending","ON_CALL",owner,3,"BM25","ASSISTANT");
        var judgment=feedback.submit(search.searchId(),"legacy-runbook-2",3,null,owner,"ON_CALL");
        jdbc.sql("UPDATE runbook_retrieval_query SET scope_type='SCOPED',scope_json='{}' WHERE id=:id").param("id",search.searchId()).update();
        assertThatThrownBy(()->feedback.review(judgment.id(),0,"APPROVE",3,null,reviewer)).isInstanceOf(ApiException.class).hasMessageContaining("范围快照无效");
        assertThat(jdbc.sql("SELECT review_status FROM runbook_relevance_judgment WHERE id=:id").param("id",judgment.id()).query(String.class).single()).isEqualTo("PENDING");
        jdbc.sql("INSERT INTO runbook_retrieval_eval_case(case_key,query_text,expected_stable_key,scope_type,scope_json) VALUES('malformed-case','Redis pending','legacy-runbook-2','SCOPED','{}')").update();
        assertThatThrownBy(()->runbooks.evaluate(reviewer)).isInstanceOf(ApiException.class);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM runbook_retrieval_eval_run").query(Integer.class).single()).isZero();
    }

    @Test void shouldPurgeScopePayloadButPreserveIndependentlyApprovedEvaluationSnapshot() {
        long owner=user("zhangwei"),reviewer=user("lina");
        long judgment=approve("Redis pending",new RunbookService.SearchScope(List.of("MID-REDIS-01"),List.of("MIDDLEWARE")),"legacy-runbook-2",owner,reviewer);
        jdbc.sql("UPDATE runbook_retrieval_query SET created_at=TIMESTAMP '2000-01-01 00:00:00'").update();
        var before=runbooks.evaluate(reviewer);
        assertThat(feedback.purgeExpired(reviewer).purgedSnapshots()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM runbook_retrieval_query WHERE scope_json IS NOT NULL").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT scope_json FROM runbook_retrieval_eval_case WHERE judgment_id=:id").param("id",judgment).query(String.class).single()).contains("MID-REDIS-01");
        assertThat(runbooks.evaluate(reviewer).datasetVersion()).isEqualTo(before.datasetVersion());
    }

    @Test void shouldNotPersistSensitiveScopeAsAnEvaluableFilter() {
        long owner=user("zhangwei"),reviewer=user("lina");
        var search=runbooks.searchTracked("Redis pending","ON_CALL",owner,3,"BM25","ASSISTANT",
                new RunbookService.SearchScope(List.of("MID-REDIS-01"),List.of("MIDDLEWARE"),1L,"INC-1","password=topsecret"));
        assertThat(jdbc.sql("SELECT scope_type FROM runbook_retrieval_query WHERE id=:id").param("id",search.searchId()).query(String.class).single()).isEqualTo("UNKNOWN");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM runbook_retrieval_query WHERE scope_json LIKE '%topsecret%'").query(Integer.class).single()).isZero();
        var judgment=feedback.submit(search.searchId(),"legacy-runbook-2",3,null,owner,"ON_CALL");
        assertThat(feedback.review(judgment.id(),0,"APPROVE",3,null,reviewer).promotedCaseKey()).isNull();
    }

    private long approve(String question,RunbookService.SearchScope scope,String key,long owner,long reviewer) {
        var search=runbooks.searchTracked(question,"ON_CALL",owner,3,"BM25","ASSISTANT",scope);
        assertThat(search.results()).extracting(RunbookService.SearchResult::stableKey).contains(key);
        var judgment=feedback.submit(search.searchId(),key,3,"scoped acceptance",owner,"ON_CALL");
        assertThat(feedback.review(judgment.id(),0,"APPROVE",3,"independent",reviewer).promotedCaseKey()).isNotNull();
        return judgment.id();
    }
    private long user(String username) {
        return jdbc.sql("SELECT id FROM sys_user WHERE username=:name").param("name",username).query(Long.class).single();
    }
}
