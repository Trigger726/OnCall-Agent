package org.trigger.opspilot.runbook;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class RunbookRetrievalScopeMigrationTest {
    @Test void shouldMarkAmbiguousAssistantHistoryWithoutRewritingOldPayloadsOrEnabledFlags() throws Exception {
        String url="jdbc:h2:mem:scope-upgrade;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").target("42").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement()) {
            for(String source:List.of("CONSOLE","ASSISTANT")) {
                s.executeUpdate("INSERT INTO runbook_retrieval_query(query_text,query_hash,source_type,requested_mode,actual_engine,role_code,semantic_status,semantic_coverage,candidate_chunk_count,top_k,latency_ms,results_json,created_by) VALUES('same question','original-hash','"+source+"','BM25','BM25_LOCAL_V1','ON_CALL','DISABLED',0,6,3,1,'[]',2)");
                s.executeUpdate("INSERT INTO runbook_relevance_judgment(search_id,document_stable_key,relevance_grade,judged_by,review_status,reviewer_grade,reviewed_by) SELECT MAX(id),'legacy-runbook-1',3,2,'APPROVED',3,3 FROM runbook_retrieval_query");
                s.executeUpdate("INSERT INTO runbook_retrieval_eval_case(case_key,query_text,expected_stable_key,judgment_id,source_type) SELECT 'scope-old-"+source+"','same question','legacy-runbook-1',MAX(id),'HUMAN_JUDGMENT' FROM runbook_relevance_judgment");
            }
            var before=rows(s,"SELECT id,query_text,query_hash,source_type,results_json,created_by FROM runbook_retrieval_query ORDER BY id",6);
            var labels=rows(s,"SELECT id,case_key,query_text,expected_stable_key,enabled,judgment_id FROM runbook_retrieval_eval_case ORDER BY id",6);
            var checksums=rows(s,"SELECT version,checksum FROM flyway_schema_history WHERE success=TRUE ORDER BY installed_rank",2);
            assertThat(Flyway.configure().dataSource(url,"sa","").target("43").load().migrate().migrationsExecuted).isEqualTo(1);
            assertThat(rows(s,"SELECT id,query_text,query_hash,source_type,results_json,created_by FROM runbook_retrieval_query ORDER BY id",6)).isEqualTo(before);
            assertThat(rows(s,"SELECT id,case_key,query_text,expected_stable_key,enabled,judgment_id FROM runbook_retrieval_eval_case ORDER BY id",6)).isEqualTo(labels);
            assertThat(rows(s,"SELECT source_type,scope_type,scope_json FROM runbook_retrieval_query ORDER BY id",3)).containsExactly("CONSOLE|GLOBAL|null","ASSISTANT|UNKNOWN|null");
            assertThat(rows(s,"SELECT scope_type FROM runbook_retrieval_eval_case WHERE case_key LIKE 'scope-old-%' ORDER BY case_key",1)).containsExactly("UNKNOWN","GLOBAL");
            assertThat(rows(s,"SELECT version,checksum FROM flyway_schema_history WHERE success=TRUE AND (version IS NULL OR version<>'43') ORDER BY installed_rank",2)).isEqualTo(checksums);
            assertThat(Flyway.configure().dataSource(url,"sa","").target("43").load().migrate().migrationsExecuted).isZero();
        }
    }
    private static List<String> rows(java.sql.Statement s,String sql,int columns) throws Exception {
        var result=new ArrayList<String>();try(var rows=s.executeQuery(sql)){while(rows.next()){var values=new ArrayList<String>();for(int i=1;i<=columns;i++)values.add(String.valueOf(rows.getString(i)));result.add(String.join("|",values));}}return result;
    }
}
