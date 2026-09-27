package org.trigger.opspilot.runbook;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.trigger.opspilot.common.ApiException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class RunbookRetrievalTrendService {
    private final JdbcClient jdbc;

    public RunbookRetrievalTrendService(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Trend trend(LocalDate from, LocalDate to, String source, String engine, int topK) {
        LocalDateTime measuredAt = jdbc.sql("SELECT CURRENT_TIMESTAMP")
                .query((rs, row) -> rs.getObject(1, LocalDateTime.class)).single();
        LocalDate today = measuredAt.toLocalDate();
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end) || ChronoUnit.DAYS.between(start, end) >= 90) {
            throw invalid("日期范围必须正序且最多90天");
        }
        if (end.isAfter(today)) throw invalid("日期范围不能包含未来日期");
        String selectedSource = code(source, "CONSOLE");
        String selectedEngine = code(engine, Bm25Retriever.ENGINE);
        if (!Set.of("CONSOLE", "AGENT", "ALL").contains(selectedSource)) throw invalid("source 必须为 CONSOLE / AGENT / ALL");
        if (!Set.of(Bm25Retriever.ENGINE, ReciprocalRankFusion.ENGINE).contains(selectedEngine)) {
            throw invalid("engine 必须指定实际执行的 BM25_LOCAL_V1 / HYBRID_RRF_V1");
        }
        if (topK < 1 || topK > 10) throw invalid("topK 必须在1–10之间");
        var rows = jdbc.sql("""
                WITH cohort AS (
                  SELECT q.id, q.created_at, q.returned_document_count AS documents, q.snapshot_status,
                    COUNT(DISTINCT CASE WHEN j.review_status = 'APPROVED'
                      AND j.reviewer_grade BETWEEN 0 AND 3 AND j.reviewed_by <> j.judged_by
                      THEN j.document_stable_key END) AS reviewed,
                    COUNT(DISTINCT CASE WHEN j.review_status = 'APPROVED'
                      AND j.reviewer_grade BETWEEN 2 AND 3 AND j.reviewed_by <> j.judged_by
                      THEN j.document_stable_key END) AS relevant
                  FROM runbook_retrieval_query q
                  LEFT JOIN runbook_relevance_judgment j ON j.search_id = q.id
                  WHERE q.created_at >= :start AND q.created_at < :end
                    AND (:source = 'ALL' OR q.source_type = :source)
                    AND q.actual_engine = :engine AND q.top_k = :k
                  GROUP BY q.id, q.created_at, q.returned_document_count, q.snapshot_status
                )
                SELECT CAST(created_at AS DATE) AS retrieval_day, COUNT(*) AS queries,
                  SUM(CASE WHEN documents > 0 THEN 1 ELSE 0 END) AS returned,
                  SUM(CASE WHEN documents = 0 THEN 1 ELSE 0 END) AS empty_count,
                  SUM(CASE WHEN documents IS NULL THEN 1 ELSE 0 END) AS unknown_count,
                  SUM(CASE WHEN documents > 0 AND reviewed = documents THEN 1 ELSE 0 END) AS complete,
                  SUM(CASE WHEN documents > 0 AND reviewed > 0 AND reviewed < documents THEN 1 ELSE 0 END) AS partial,
                  SUM(CASE WHEN documents > 0 AND (reviewed = 0 OR reviewed > documents) THEN 1 ELSE 0 END) AS unreviewed,
                  SUM(CASE WHEN documents > 0 AND reviewed = documents AND relevant > 0 THEN 1 ELSE 0 END) AS hits,
                  SUM(CASE WHEN snapshot_status = 'PURGED' THEN 1 ELSE 0 END) AS purged
                FROM cohort GROUP BY CAST(created_at AS DATE) ORDER BY retrieval_day
                """).param("start", start.atStartOfDay()).param("end", end.plusDays(1).atStartOfDay())
                .param("source", selectedSource).param("engine", selectedEngine).param("k", topK)
                .query((rs, row) -> new Day(rs.getObject("retrieval_day", LocalDate.class), counts(
                        rs.getLong("queries"), rs.getLong("returned"), rs.getLong("empty_count"),
                        rs.getLong("unknown_count"), rs.getLong("complete"), rs.getLong("partial"),
                        rs.getLong("unreviewed"), rs.getLong("hits"), rs.getLong("purged")))).list();
        var byDate = new java.util.HashMap<LocalDate, Counts>();
        rows.forEach(row -> byDate.put(row.date(), row.counts()));
        var days = new ArrayList<Day>();
        long[] sum = new long[9];
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            Counts c = byDate.getOrDefault(day, counts(0, 0, 0, 0, 0, 0, 0, 0, 0));
            days.add(new Day(day, c));
            sum[0] += c.queryCount(); sum[1] += c.returnedQueries(); sum[2] += c.emptyQueries();
            sum[3] += c.unknownResultQueries(); sum[4] += c.fullyReviewedQueries();
            sum[5] += c.partialReviewedQueries(); sum[6] += c.unreviewedQueries();
            sum[7] += c.relevantQueries(); sum[8] += c.purgedQueries();
        }
        return new Trend(start, end, measuredAt, "DATABASE_LOCAL_DATE", selectedSource, selectedEngine, topK,
                2, counts(sum[0], sum[1], sum[2], sum[3], sum[4], sum[5], sum[6], sum[7], sum[8]),
                List.copyOf(days), "按查询发生日归属、截至读取时的独立复核结果；空结果为已知未命中，"
                + "非空结果仅全量复核计分。部分复核/历史结果未知不计入质量分母，不能代表全部流量或生产效果；"
                + "K为返回片段上限，评分按文档去重，不是去重文档Top-K排序指标；固定实际引擎和K，"
                + "可能混合角色/查询难度；离线评测不计入。仅已成功持久化的查询，非总请求量。");
    }

    private static Counts counts(long queries, long returned, long empty, long unknown, long complete,
                                 long partial, long unreviewed, long hits, long purged) {
        return new Counts(queries, returned, empty, unknown, complete, partial, unreviewed,
                complete + empty, hits, purged, rate(returned, returned + empty),
                rate(complete, returned), rate(hits, complete + empty));
    }

    private static BigDecimal rate(long numerator, long denominator) {
        return denominator == 0 ? null : BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }

    private static String code(String raw, String fallback) {
        return raw == null || raw.isBlank() ? fallback : raw.trim().toUpperCase(Locale.ROOT);
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "RUNBOOK_TREND_FILTER_INVALID", message);
    }

    public record Counts(long queryCount, long returnedQueries, long emptyQueries, long unknownResultQueries,
                         long fullyReviewedQueries, long partialReviewedQueries, long unreviewedQueries,
                         long qualityEligibleQueries, long relevantQueries, long purgedQueries,
                         BigDecimal returnRate, BigDecimal reviewCoverage, BigDecimal reviewedHitRateAtK) { }
    public record Day(LocalDate date, Counts counts) { }
    public record Trend(LocalDate from, LocalDate to, LocalDateTime measuredAt, String dateBasis,
                        String source, String engine, int topK, int relevantGradeThreshold,
                        Counts totals, List<Day> days, String note) { }
}
