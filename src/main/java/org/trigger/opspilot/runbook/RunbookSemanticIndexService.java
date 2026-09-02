package org.trigger.opspilot.runbook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ai.embedding.EmbeddingModel;
import org.trigger.opspilot.audit.AuditService;
import org.trigger.opspilot.common.ApiException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class RunbookSemanticIndexService {
    static final String PROVIDER_FAILURE = "RUNBOOK_EMBEDDING_PROVIDER_FAILED";

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<EmbeddingModel> embeddingModels;
    private final TransactionTemplate transactionTemplate;
    private final AuditService auditService;
    private final boolean enabled;
    private final String provider;
    private final String model;
    private final int batchSize;
    private final double minimumCoverage;

    public RunbookSemanticIndexService(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            ObjectProvider<EmbeddingModel> embeddingModels,
            TransactionTemplate transactionTemplate,
            AuditService auditService,
            @Value("${opspilot.runbook.semantic.enabled:false}") boolean enabled,
            @Value("${opspilot.runbook.semantic.provider:dashscope}") String provider,
            @Value("${opspilot.runbook.semantic.model:text-embedding-v4}") String model,
            @Value("${opspilot.runbook.semantic.batch-size:10}") int batchSize,
            @Value("${opspilot.runbook.semantic.minimum-coverage:1.0}") double minimumCoverage) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.embeddingModels = embeddingModels;
        this.transactionTemplate = transactionTemplate;
        this.auditService = auditService;
        this.enabled = enabled;
        this.provider = provider.trim().toLowerCase();
        this.model = model.trim();
        this.batchSize = Math.max(1, Math.min(50, batchSize));
        this.minimumCoverage = Math.max(0.1, Math.min(1.0, minimumCoverage));
    }

    public IndexStatus status() {
        int published = jdbcClient.sql("""
                        SELECT COUNT(*) FROM runbook_chunk c
                        JOIN runbook_document d ON d.id = c.document_id
                        WHERE d.status = 'PUBLISHED'
                        """).query(Integer.class).single();
        int indexed = jdbcClient.sql("""
                        SELECT COUNT(*) FROM runbook_chunk_embedding e
                        JOIN runbook_chunk c ON c.id = e.chunk_id
                        JOIN runbook_document d ON d.id = c.document_id
                        WHERE e.provider = :provider AND e.model = :model AND d.status = 'PUBLISHED'
                        """).param("provider", provider).param("model", model).query(Integer.class).single();
        BigDecimal coverage = published == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(indexed).divide(BigDecimal.valueOf(published), 4, RoundingMode.HALF_UP);
        LatestRun latest = latestRun();
        String state;
        String note;
        if (!enabled) {
            state = "DISABLED";
            note = "语义检索未启用，AUTO 模式使用 BM25";
        } else if (embeddingModel() == null) {
            state = "PROVIDER_UNAVAILABLE";
            note = "未发现 EmbeddingModel，AUTO 模式使用 BM25";
        } else if (published == 0 || indexed == 0) {
            state = "EMPTY";
            note = "尚未构建当前模型的向量索引";
        } else if (coverage.doubleValue() < minimumCoverage) {
            state = "PARTIAL";
            note = "向量覆盖率不足，需重建索引后才进入混合检索";
        } else {
            state = "READY";
            note = "向量索引覆盖当前已发布分块";
        }
        return new IndexStatus(enabled, state, provider, model, published, indexed, coverage,
                latest == null ? null : latest.id(), latest == null ? null : latest.status(),
                latest == null ? null : latest.completedAt(), note);
    }

    public IndexBuildResult rebuild(Long actorId) {
        if (!enabled) {
            throw new ApiException(HttpStatus.CONFLICT, "RUNBOOK_SEMANTIC_DISABLED",
                    "请先启用 RUNBOOK_SEMANTIC_ENABLED 并配置模型凭证");
        }
        EmbeddingModel embeddingModel = embeddingModel();
        if (embeddingModel == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RUNBOOK_EMBEDDING_PROVIDER_UNAVAILABLE",
                    "Embedding Provider 尚未就绪");
        }
        List<IndexDocument> documents = loadPublishedDocuments();
        if (documents.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "RUNBOOK_INDEX_SOURCE_EMPTY", "没有可索引的已发布分块");
        }
        String fingerprint = fingerprint(documents);
        Long reusableRun = reusableRun(fingerprint, documents.size());
        if (reusableRun != null) {
            return new IndexBuildResult(reusableRun, true, provider, model, documents.size(),
                    currentDimensions(), fingerprint, status());
        }

        long runId = createRun(fingerprint, documents.size(), actorId);
        List<IndexedVector> vectors = new ArrayList<>(documents.size());
        try {
            for (int offset = 0; offset < documents.size(); offset += batchSize) {
                List<IndexDocument> batch = documents.subList(offset, Math.min(offset + batchSize, documents.size()));
                List<float[]> embeddings = embeddingModel.embed(batch.stream().map(IndexDocument::text).toList());
                if (embeddings == null || embeddings.size() != batch.size()) {
                    throw new IllegalStateException("Embedding batch size mismatch");
                }
                for (int index = 0; index < batch.size(); index++) {
                    float[] vector = embeddings.get(index);
                    validateVector(vector, vectors.isEmpty() ? null : vectors.get(0).vector().length);
                    vectors.add(new IndexedVector(batch.get(index).chunkId(), batch.get(index).contentHash(), vector));
                }
            }
            int dimensions = vectors.get(0).vector().length;
            transactionTemplate.executeWithoutResult(status -> {
                jdbcClient.sql("DELETE FROM runbook_chunk_embedding WHERE provider = :provider AND model = :model")
                        .param("provider", provider).param("model", model).update();
                for (IndexedVector vector : vectors) {
                    jdbcClient.sql("""
                                    INSERT INTO runbook_chunk_embedding(
                                      chunk_id, provider, model, content_hash, dimensions, embedding_json)
                                    VALUES (:chunkId, :provider, :model, :contentHash, :dimensions, :embedding)
                                    """)
                            .param("chunkId", vector.chunkId()).param("provider", provider).param("model", model)
                            .param("contentHash", vector.contentHash()).param("dimensions", dimensions)
                            .param("embedding", json(vector.vector())).update();
                }
                jdbcClient.sql("""
                                UPDATE runbook_embedding_index_run
                                SET status = 'COMPLETED', dimensions = :dimensions, completed_at = CURRENT_TIMESTAMP
                                WHERE id = :id
                                """).param("dimensions", dimensions).param("id", runId).update();
            });
            auditService.record("RUNBOOK_SEMANTIC_INDEX_REBUILD", "RUNBOOK_INDEX", runId,
                    provider + "/" + model + " 完成 " + vectors.size() + " 个分块，维度 " + dimensions);
            return new IndexBuildResult(runId, false, provider, model, vectors.size(), dimensions,
                    fingerprint, status());
        } catch (RuntimeException exception) {
            markFailed(runId);
            auditService.record("RUNBOOK_SEMANTIC_INDEX_FAILED", "RUNBOOK_INDEX", runId,
                    provider + "/" + model + " 失败；错误码 " + PROVIDER_FAILURE);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, PROVIDER_FAILURE,
                    "Embedding Provider 调用失败，原有索引未被覆盖");
        }
    }

    RankOutcome rank(String query, List<SemanticDocument> candidates, int topK) {
        IndexStatus indexStatus = status();
        if (!"READY".equals(indexStatus.state())) {
            return new RankOutcome(false, indexStatus.state(), indexStatus.coverage(), List.of(), indexStatus.note());
        }
        EmbeddingModel embeddingModel = embeddingModel();
        if (embeddingModel == null) {
            return new RankOutcome(false, "PROVIDER_UNAVAILABLE", indexStatus.coverage(), List.of(),
                    "Embedding Provider 不可用，已降级 BM25");
        }
        Map<Long, float[]> vectors = loadVectors();
        long covered = candidates.stream().filter(candidate -> vectors.containsKey(candidate.chunkId())).count();
        BigDecimal visibleCoverage = candidates.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(covered).divide(BigDecimal.valueOf(candidates.size()), 4, RoundingMode.HALF_UP);
        if (visibleCoverage.doubleValue() < minimumCoverage) {
            return new RankOutcome(false, "PARTIAL", visibleCoverage, List.of(),
                    "当前角色候选分块的向量覆盖率不足，已降级 BM25");
        }
        try {
            List<float[]> queryVectors = embeddingModel.embed(List.of(query));
            if (queryVectors == null || queryVectors.size() != 1) throw new IllegalStateException("Missing query vector");
            float[] queryVector = queryVectors.get(0);
            validateVector(queryVector, vectors.values().stream().findFirst().map(value -> value.length).orElse(null));
            List<SemanticScore> scores = candidates.stream()
                    .filter(candidate -> vectors.containsKey(candidate.chunkId()))
                    .map(candidate -> new SemanticScore(candidate.chunkId(), cosine(queryVector, vectors.get(candidate.chunkId()))))
                    .sorted(Comparator.comparingDouble(SemanticScore::score).reversed()
                            .thenComparingLong(SemanticScore::chunkId))
                    .limit(topK)
                    .toList();
            return new RankOutcome(true, "READY", visibleCoverage, scores, null);
        } catch (RuntimeException exception) {
            return new RankOutcome(false, "DEGRADED", visibleCoverage, List.of(),
                    "Embedding Provider 查询失败，已降级 BM25");
        }
    }

    private List<IndexDocument> loadPublishedDocuments() {
        return jdbcClient.sql("""
                        SELECT c.id, c.heading, c.content, d.resource_type, d.service_code, d.title, d.summary
                        FROM runbook_chunk c JOIN runbook_document d ON d.id = c.document_id
                        WHERE d.status = 'PUBLISHED' ORDER BY c.id
                        """)
                .query((rs, rowNum) -> {
                    String text = rs.getString("title") + " " + rs.getString("resource_type") + " "
                            + nullable(rs.getString("service_code")) + nullable(rs.getString("summary"))
                            + rs.getString("heading") + " " + rs.getString("content");
                    return new IndexDocument(rs.getLong("id"), text, sha256(text));
                }).list();
    }

    private Map<Long, float[]> loadVectors() {
        Map<Long, float[]> vectors = new HashMap<>();
        jdbcClient.sql("""
                        SELECT chunk_id, embedding_json FROM runbook_chunk_embedding
                        WHERE provider = :provider AND model = :model
                        """).param("provider", provider).param("model", model)
                .query((rs, rowNum) -> new StoredVector(rs.getLong("chunk_id"), parseVector(rs.getString("embedding_json"))))
                .list().forEach(item -> vectors.put(item.chunkId(), item.vector()));
        return vectors;
    }

    private Long reusableRun(String fingerprint, int chunkCount) {
        Long runId = jdbcClient.sql("""
                        SELECT id FROM runbook_embedding_index_run
                        WHERE provider = :provider AND model = :model AND status = 'COMPLETED'
                          AND content_fingerprint = :fingerprint AND chunk_count = :chunkCount
                        ORDER BY id DESC LIMIT 1
                        """).param("provider", provider).param("model", model)
                .param("fingerprint", fingerprint).param("chunkCount", chunkCount)
                .query(Long.class).optional().orElse(null);
        return runId != null && indexedCount() == chunkCount ? runId : null;
    }

    private long createRun(String fingerprint, int chunkCount, Long actorId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql("""
                        INSERT INTO runbook_embedding_index_run(
                          provider, model, status, content_fingerprint, chunk_count, created_by)
                        VALUES (:provider, :model, 'RUNNING', :fingerprint, :chunkCount, :createdBy)
                        """).param("provider", provider).param("model", model).param("fingerprint", fingerprint)
                .param("chunkCount", chunkCount).param("createdBy", actorId).update(keyHolder, "id");
        if (keyHolder.getKey() == null) throw new IllegalStateException("Missing semantic index run id");
        return keyHolder.getKey().longValue();
    }

    private void markFailed(long runId) {
        transactionTemplate.executeWithoutResult(status -> jdbcClient.sql("""
                        UPDATE runbook_embedding_index_run
                        SET status = 'FAILED', error_code = :errorCode, completed_at = CURRENT_TIMESTAMP
                        WHERE id = :id
                        """).param("errorCode", PROVIDER_FAILURE).param("id", runId).update());
    }

    private LatestRun latestRun() {
        return jdbcClient.sql("""
                        SELECT id, status, completed_at FROM runbook_embedding_index_run
                        WHERE provider = :provider AND model = :model ORDER BY id DESC LIMIT 1
                        """).param("provider", provider).param("model", model)
                .query((rs, rowNum) -> new LatestRun(rs.getLong("id"), rs.getString("status"),
                        rs.getObject("completed_at", LocalDateTime.class))).optional().orElse(null);
    }

    private int indexedCount() {
        return jdbcClient.sql("""
                        SELECT COUNT(*) FROM runbook_chunk_embedding
                        WHERE provider = :provider AND model = :model
                        """).param("provider", provider).param("model", model).query(Integer.class).single();
    }

    private Integer currentDimensions() {
        return jdbcClient.sql("""
                        SELECT MAX(dimensions) FROM runbook_chunk_embedding
                        WHERE provider = :provider AND model = :model
                        """).param("provider", provider).param("model", model).query(Integer.class).optional().orElse(null);
    }

    private EmbeddingModel embeddingModel() {
        return embeddingModels.orderedStream().findFirst().orElse(null);
    }

    private String fingerprint(List<IndexDocument> documents) {
        return sha256(provider + ":" + model + ":" + documents.stream()
                .map(item -> item.chunkId() + ":" + item.contentHash()).toList());
    }

    private String json(float[] vector) {
        try {
            return objectMapper.writeValueAsString(vector);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize embedding", exception);
        }
    }

    private float[] parseVector(String value) {
        try {
            return objectMapper.readValue(value, float[].class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot parse stored embedding", exception);
        }
    }

    private static void validateVector(float[] vector, Integer expectedDimensions) {
        if (vector == null || vector.length == 0) throw new IllegalStateException("Empty embedding");
        if (expectedDimensions != null && vector.length != expectedDimensions) {
            throw new IllegalStateException("Embedding dimensions mismatch");
        }
        double norm = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalStateException("Non-finite embedding");
            norm += value * value;
        }
        if (norm == 0) throw new IllegalStateException("Zero embedding");
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        return Math.round(dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm)) * 1_000_000.0) / 1_000_000.0;
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? "" : value + " ";
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    record SemanticDocument(long chunkId, String text) {
    }

    record SemanticScore(long chunkId, double score) {
    }

    record RankOutcome(boolean usable, String state, BigDecimal coverage,
                       List<SemanticScore> scores, String warning) {
    }

    public record IndexStatus(boolean enabled, String state, String provider, String model,
                              int publishedChunkCount, int indexedChunkCount, BigDecimal coverage,
                              Long latestRunId, String latestRunStatus, LocalDateTime latestCompletedAt,
                              String note) {
    }

    public record IndexBuildResult(long runId, boolean reused, String provider, String model,
                                   int chunkCount, Integer dimensions, String contentFingerprint,
                                   IndexStatus status) {
    }

    private record IndexDocument(long chunkId, String text, String contentHash) {
    }

    private record IndexedVector(long chunkId, String contentHash, float[] vector) {
    }

    private record StoredVector(long chunkId, float[] vector) {
    }

    private record LatestRun(long id, String status, LocalDateTime completedAt) {
    }
}
