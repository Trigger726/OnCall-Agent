package org.trigger.opspilot.runbook;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Shared persisted fixtures: not production traffic or seed evaluation scores. */
public final class RetrievalTrendScenarios {
    private RetrievalTrendScenarios() { }

    public static void verify(JdbcClient jdbc, RunbookRetrievalTrendService service) {
        LocalDate day = LocalDate.of(1999, 3, 1);
        long hit = query(jdbc, "1999-03-01 00:00:00", "CONSOLE", "BM25_LOCAL_V1", 3, 2, "ACTIVE");
        review(jdbc, hit, "one", "APPROVED", 2, 1, 2);
        review(jdbc, hit, "two", "APPROVED", 0, 1, 2);
        long miss = query(jdbc, "1999-03-01 12:00:00", "CONSOLE", "BM25_LOCAL_V1", 3, 1, "PURGED");
        review(jdbc, miss, "one", "APPROVED", 1, 1, 2);
        query(jdbc, "1999-03-01 13:00:00", "CONSOLE", "BM25_LOCAL_V1", 3, 0, "PURGED");
        long partial = query(jdbc, "1999-03-01 14:00:00", "CONSOLE", "BM25_LOCAL_V1", 3, 2, "ACTIVE");
        review(jdbc, partial, "one", "APPROVED", 3, 1, 2);
        review(jdbc, partial, "two", "PENDING", null, 1, null);
        long rejected = query(jdbc, "1999-03-01 15:00:00", "CONSOLE", "BM25_LOCAL_V1", 3, 1, "ACTIVE");
        review(jdbc, rejected, "one", "REJECTED", 3, 1, 2);
        long self = query(jdbc, "1999-03-01 16:00:00", "CONSOLE", "BM25_LOCAL_V1", 3, 1, "ACTIVE");
        review(jdbc, self, "one", "APPROVED", 3, 1, 1);
        query(jdbc, "1999-03-01 17:00:00", "CONSOLE", "BM25_LOCAL_V1", 3, null, "PURGED");
        query(jdbc, "1999-03-01 18:00:00", "AGENT", "BM25_LOCAL_V1", 3, 0, "ACTIVE");
        query(jdbc, "1999-03-01 19:00:00", "CONSOLE", "HYBRID_RRF_V1", 3, 0, "ACTIVE");
        query(jdbc, "1999-03-01 20:00:00", "CONSOLE", "BM25_LOCAL_V1", 5, 0, "ACTIVE");
        query(jdbc, "1999-03-03 00:00:00", "CONSOLE", "BM25_LOCAL_V1", 3, 0, "ACTIVE");

        var view = service.trend(day, day.plusDays(1), "CONSOLE", "BM25_LOCAL_V1", 3);
        var totals = view.totals();
        assertThat(totals.queryCount()).isEqualTo(7);
        assertThat(totals.returnedQueries()).isEqualTo(5);
        assertThat(totals.emptyQueries()).isEqualTo(1);
        assertThat(totals.unknownResultQueries()).isEqualTo(1);
        assertThat(totals.fullyReviewedQueries()).isEqualTo(2);
        assertThat(totals.partialReviewedQueries()).isEqualTo(1);
        assertThat(totals.unreviewedQueries()).isEqualTo(2);
        assertThat(totals.qualityEligibleQueries()).isEqualTo(3);
        assertThat(totals.relevantQueries()).isEqualTo(1);
        assertThat(totals.purgedQueries()).isEqualTo(3);
        assertThat(totals.returnRate()).isEqualByComparingTo(new BigDecimal("0.833333"));
        assertThat(totals.reviewCoverage()).isEqualByComparingTo(new BigDecimal("0.400000"));
        assertThat(totals.reviewedHitRateAtK()).isEqualByComparingTo(new BigDecimal("0.333333"));
        assertThat(view.days()).hasSize(2);
        assertThat(view.days().get(1).counts().queryCount()).isZero();
        assertThat(view.days().get(1).counts().returnRate()).isNull();
        assertThat(view.days().get(1).counts().reviewedHitRateAtK()).isNull();
        var extended = service.trend(day, day.plusDays(2), "CONSOLE", "BM25_LOCAL_V1", 3);
        assertThat(extended.totals().queryCount()).isEqualTo(8);
        assertThat(extended.totals().reviewedHitRateAtK()).isEqualByComparingTo("0.250000");
        assertThat(extended.days().get(2).counts().reviewedHitRateAtK()).isEqualByComparingTo("0.000000");
        assertThat(service.trend(day, day, "AGENT", "BM25_LOCAL_V1", 3).totals().queryCount()).isEqualTo(1);
        assertThat(service.trend(day, day, "CONSOLE", "HYBRID_RRF_V1", 3).totals().queryCount()).isEqualTo(1);
        assertThat(service.trend(day, day, "CONSOLE", "BM25_LOCAL_V1", 5).totals().queryCount()).isEqualTo(1);
        assertThat(service.trend(day, day, "ALL", "BM25_LOCAL_V1", 3).totals().queryCount()).isEqualTo(8);

        assertThatThrownBy(() -> service.trend(day.plusDays(1), day, "CONSOLE", "BM25_LOCAL_V1", 3))
                .hasMessageContaining("日期");
        assertThatThrownBy(() -> service.trend(day, day.plusDays(90), "CONSOLE", "BM25_LOCAL_V1", 3))
                .hasMessageContaining("90");
        assertThatThrownBy(() -> service.trend(day, day, "SEED", "BM25_LOCAL_V1", 3))
                .hasMessageContaining("source");
        assertThatThrownBy(() -> service.trend(day, day, "CONSOLE", "AUTO", 3))
                .hasMessageContaining("engine");
        assertThatThrownBy(() -> service.trend(day, day, "CONSOLE", "BM25_LOCAL_V1", 0))
                .hasMessageContaining("topK");
        assertThatThrownBy(() -> service.trend(LocalDate.of(9999, 1, 1), LocalDate.of(9999, 1, 1),
                "CONSOLE", "BM25_LOCAL_V1", 3)).hasMessageContaining("未来");
    }

    private static long query(JdbcClient jdbc, String at, String source, String engine, int k,
                              Integer count, String snapshot) {
        var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO runbook_retrieval_query(query_text, query_hash, source_type, requested_mode,
                  actual_engine, role_code, semantic_status, semantic_coverage, candidate_chunk_count,
                  top_k, latency_ms, results_json, returned_document_count, snapshot_status, created_at)
                VALUES ('TREND_FIXTURE_DO_NOT_EXPOSE', 'fixture', :source, 'AUTO', :engine,
                  'ADMIN', 'NOT_REQUESTED', 0, 3, :k, 1, '[]', :count, :snapshot, :at)
                """).param("source", source).param("engine", engine).param("k", k)
                .param("count", count).param("snapshot", snapshot)
                .param("at", java.time.LocalDateTime.parse(at.replace(' ', 'T'))).update(key, "id");
        return key.getKey().longValue();
    }

    private static void review(JdbcClient jdbc, long search, String doc, String status,
                               Integer grade, long judge, Integer reviewer) {
        jdbc.sql("""
                INSERT INTO runbook_relevance_judgment(search_id, document_stable_key, relevance_grade,
                  judged_by, review_status, reviewer_grade, reviewed_by)
                VALUES (:search, :doc, 3, :judge, :status, :grade, :reviewer)
                """).param("search", search).param("doc", doc).param("judge", judge)
                .param("status", status).param("grade", grade).param("reviewer", reviewer).update();
    }
}
