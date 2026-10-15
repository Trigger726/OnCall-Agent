package org.trigger.opspilot.runbook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@SpringBootTest(properties={
        "spring.datasource.url=jdbc:h2:mem:opspilot-document-diversity;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.ai.dashscope.api-key=disabled", "opspilot.ai.enabled=false", "opspilot.runbook.semantic.enabled=true"
})
class RunbookDocumentDiversityIntegrationTest extends DocumentDiversityScenarios {}

@Transactional
abstract class DocumentDiversityScenarios {
    @Autowired RunbookService runbooks;
    @Autowired RunbookPublicationService publications;
    @Autowired RunbookSemanticIndexService index;
    @Autowired JdbcClient jdbc;
    @MockBean EmbeddingModel embeddings;
    private static final String QUERY="diversityprobe";
    private static final List<String> KEYS=List.of("diversity-long","diversity-second","diversity-third");
    private static final RunbookService.SearchScope SCOPE=new RunbookService.SearchScope(List.of("APP-AUTH"),List.of("APPLICATION"));

    @BeforeEach void embeddingFixture() {
        // Deterministic local vectors exercise the real semantic-index/RRF path, not cloud quality.
        when(embeddings.embed(anyList())).thenAnswer(call -> ((List<String>)call.getArgument(0)).stream()
                .map(text -> new float[]{text.contains(QUERY)?1f:-1f,0f}).toList());
    }

    @Test void shouldFillTopThreeWithDistinctBooksBeforeTheCandidateWindow() {
        corpus();
        var result=runbooks.searchTracked(QUERY,"ON_CALL",2L,3,"BM25","ASSISTANT",SCOPE);
        assertThat(result.results()).extracting(RunbookService.SearchResult::stableKey).containsExactlyInAnyOrderElementsOf(KEYS);
        assertThat(result.results()).allSatisfy(hit -> assertThat(hit.citation())
                .contains("runbook:"+hit.stableKey()+":v1#chunk-"));
    }

    @Test void shouldDiversifyRealSemanticRankingAndFusionWithoutInventingCitations() {
        corpus();index.rebuild(1L);
        var result=runbooks.searchTracked(QUERY,"ON_CALL",2L,3,"HYBRID","ASSISTANT",SCOPE);
        assertThat(result.engine()).isEqualTo(ReciprocalRankFusion.ENGINE);
        assertThat(result.results()).extracting(RunbookService.SearchResult::stableKey).containsExactlyInAnyOrderElementsOf(KEYS);
        assertThat(result.results()).allSatisfy(hit -> {
            assertThat(hit.semanticRank()).isNotNull();
            assertThat(jdbc.sql("SELECT COUNT(*) FROM runbook_chunk WHERE document_id=:doc AND chunk_index=:chunk")
                    .param("doc",hit.documentId()).param("chunk",hit.chunkIndex()).query(Integer.class).single()).isEqualTo(1);
        });
    }

    @Test void shouldKeepScopePublicationAndAclFencesBeforeDocumentSelection() {
        corpus();
        publish("diversity-foreign","APP-OTHER",List.of("ON_CALL"));
        publish("diversity-private","APP-AUTH",List.of("ADMIN"));
        runbooks.importMarkdown(command("diversity-draft","APP-AUTH",List.of("ON_CALL"),3),1L);
        index.rebuild(1L);
        for(String mode:List.of("BM25","HYBRID")) {
            var result=runbooks.searchTracked(QUERY,"ON_CALL",2L,3,mode,"ASSISTANT",SCOPE);
            assertThat(result.results()).extracting(RunbookService.SearchResult::stableKey).containsExactlyInAnyOrderElementsOf(KEYS);
        }
    }

    @Test void shouldEvaluateTheSameDistinctDocumentBudgetAsLiveSearch() {
        corpus();index.rebuild(1L);
        jdbc.sql("UPDATE runbook_retrieval_eval_case SET enabled=FALSE").update();
        for(String key:KEYS) jdbc.sql("INSERT INTO runbook_retrieval_eval_case(case_key,query_text,expected_stable_key) VALUES(:key,:q,:key)")
                .param("key",key).param("q",QUERY).update();
        var evaluated=runbooks.evaluate(3L);
        assertThat(evaluated.caseCount()).isEqualTo(1);
        assertThat(evaluated.judgmentCount()).isEqualTo(3);
        assertThat(evaluated.recallAt3()).isEqualByComparingTo("1");
        assertThat(evaluated.metrics().get(1).recallAt3()).isEqualByComparingTo("1");
    }

    private void corpus() {
        // 35 matching sections exceed the normal candidate window (20).
        var longBook=runbooks.importMarkdown(command(KEYS.get(0),"APP-AUTH",List.of("ADMIN","ON_CALL"),35),1L).document();
        approve(longBook.id());
        publish(KEYS.get(1),"APP-AUTH",List.of("ADMIN","ON_CALL"));
        publish(KEYS.get(2),"APP-AUTH",List.of("ADMIN","ON_CALL"));
    }
    private void publish(String key,String code,List<String> roles) {
        approve(runbooks.importMarkdown(command(key,code,roles,1),1L).document().id());
    }
    private void approve(long id) {publications.decide(id,0,"APPROVE",UUID.randomUUID().toString(),"独立复核测试手册",3L);}
    private RunbookService.ImportCommand command(String key,String code,List<String> roles,int sections) {
        var markdown=new StringBuilder();
        for(int i=0;i<sections;i++) markdown.append("## ").append(QUERY).append(" chapter ").append(i)
                .append("\n").append(QUERY).append(" verify connection ownership and restore the service safely.\n\n");
        return new RunbookService.ImportCommand(key,"APPLICATION",code,
                sections>1?QUERY:"连接审计",null,"test/"+key+".md",markdown.toString(),roles);
    }
}
