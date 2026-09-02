package org.trigger.opspilot.runbook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.trigger.opspilot.audit.AuditService;
import org.trigger.opspilot.common.ApiException;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class RunbookService {
    private static final String LEGACY_ENGINE = "LEGACY_CONTAINS_V1";
    private static final int MAX_SOURCE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_PDF_PAGES = 200;
    private static final Pattern STABLE_KEY = Pattern.compile("[a-z0-9][a-z0-9-]{2,79}");
    private static final Pattern CODE = Pattern.compile("[A-Z0-9_.-]{2,80}");
    private static final Set<String> SUPPORTED_ROLES = Set.of("ADMIN", "OPS_MANAGER", "ON_CALL");

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final RunbookSemanticIndexService semanticIndexService;
    private final RunbookRetrievalFeedbackService feedbackService;
    private final int candidateWindow;
    private final int rrfRankConstant;

    public RunbookService(JdbcClient jdbcClient, ObjectMapper objectMapper, AuditService auditService,
                          RunbookSemanticIndexService semanticIndexService,
                          RunbookRetrievalFeedbackService feedbackService,
                          @Value("${opspilot.runbook.retrieval.candidate-window:20}") int candidateWindow,
                          @Value("${opspilot.runbook.retrieval.rrf-rank-constant:60}") int rrfRankConstant) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.semanticIndexService = semanticIndexService;
        this.feedbackService = feedbackService;
        this.candidateWindow = Math.max(10, Math.min(100, candidateWindow));
        this.rrfRankConstant = Math.max(1, Math.min(1_000, rrfRankConstant));
    }

    public List<DocumentView> listPublished(String roleCode) {
        String role = normalizeRole(roleCode);
        return jdbcClient.sql("""
                        SELECT d.id, d.stable_key, d.version_no, d.status, d.resource_type,
                               d.service_code, d.title, d.summary, d.source_type, d.source_name,
                               d.content_hash, d.markdown_content, d.created_by, d.created_at, d.published_at,
                               (SELECT COUNT(*) FROM runbook_chunk c WHERE c.document_id = d.id) AS chunk_count
                        FROM runbook_document d
                        WHERE d.status = 'PUBLISHED'
                          AND EXISTS (SELECT 1 FROM runbook_document_acl a
                                      WHERE a.document_id = d.id AND a.role_code = :role)
                        ORDER BY d.title, d.stable_key
                        """)
                .param("role", role)
                .query((rs, rowNum) -> mapDocument(rs.getLong("id"), rs.getString("stable_key"),
                        rs.getInt("version_no"), rs.getString("status"), rs.getString("resource_type"),
                        rs.getString("service_code"), rs.getString("title"), rs.getString("summary"),
                        rs.getString("source_type"), rs.getString("source_name"), rs.getString("content_hash"),
                        rs.getString("markdown_content"), rs.getObject("created_by", Long.class),
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getObject("published_at", LocalDateTime.class), rs.getInt("chunk_count")))
                .list();
    }

    public List<DocumentView> versions(String stableKey, String roleCode) {
        String normalizedKey = normalizeStableKey(stableKey);
        String role = normalizeRole(roleCode);
        List<DocumentView> versions = jdbcClient.sql("""
                        SELECT d.id, d.stable_key, d.version_no, d.status, d.resource_type,
                               d.service_code, d.title, d.summary, d.source_type, d.source_name,
                               d.content_hash, d.markdown_content, d.created_by, d.created_at, d.published_at,
                               (SELECT COUNT(*) FROM runbook_chunk c WHERE c.document_id = d.id) AS chunk_count
                        FROM runbook_document d
                        WHERE d.stable_key = :stableKey
                          AND EXISTS (SELECT 1 FROM runbook_document_acl a
                                      WHERE a.document_id = d.id AND a.role_code = :role)
                        ORDER BY d.version_no DESC
                        """)
                .param("stableKey", normalizedKey).param("role", role)
                .query((rs, rowNum) -> mapDocument(rs.getLong("id"), rs.getString("stable_key"),
                        rs.getInt("version_no"), rs.getString("status"), rs.getString("resource_type"),
                        rs.getString("service_code"), rs.getString("title"), rs.getString("summary"),
                        rs.getString("source_type"), rs.getString("source_name"), rs.getString("content_hash"),
                        rs.getString("markdown_content"), rs.getObject("created_by", Long.class),
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getObject("published_at", LocalDateTime.class), rs.getInt("chunk_count")))
                .list();
        if (versions.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RUNBOOK_NOT_FOUND", "Runbook 不存在或当前角色无权查看");
        }
        return versions;
    }

    public SearchResponse search(String rawQuery, String roleCode, int requestedTopK) {
        return search(rawQuery, roleCode, requestedTopK, "AUTO");
    }

    public SearchResponse search(String rawQuery, String roleCode, int requestedTopK, String rawMode) {
        return searchInternal(rawQuery, roleCode, requestedTopK, rawMode, null, null);
    }

    public SearchResponse searchTracked(String rawQuery, String roleCode, Long actorId, int requestedTopK,
                                        String rawMode, String sourceType) {
        return searchInternal(rawQuery, roleCode, requestedTopK, rawMode, actorId, sourceType);
    }

    private SearchResponse searchInternal(String rawQuery, String roleCode, int requestedTopK, String rawMode,
                                          Long actorId, String sourceType) {
        long startedNanos = System.nanoTime();
        String query = requireText(rawQuery, "query", 500);
        String role = normalizeRole(roleCode);
        String mode = normalizeSearchMode(rawMode);
        int topK = Math.max(1, Math.min(10, requestedTopK));
        List<SearchCandidate> candidates = loadCandidates(role);
        Map<Long, SearchCandidate> candidatesById = new HashMap<>();
        candidates.forEach(candidate -> candidatesById.put(candidate.chunkId(), candidate));
        List<Bm25Retriever.Document> documents = candidates.stream()
                .map(candidate -> new Bm25Retriever.Document(candidate.chunkId(), candidate.searchableText()))
                .toList();
        int retrievalWindow = Math.max(topK, candidateWindow);
        List<Bm25Retriever.ScoredDocument> lexicalRanking = Bm25Retriever.search(query, documents, retrievalWindow);
        Map<Long, Bm25Retriever.ScoredDocument> lexicalById = new HashMap<>();
        lexicalRanking.forEach(item -> lexicalById.put(item.document().chunkId(), item));

        boolean semanticRequested = !"BM25".equals(mode);
        RunbookSemanticIndexService.IndexStatus unrequestedIndexStatus = semanticRequested
                ? null : semanticIndexService.status();
        RunbookSemanticIndexService.RankOutcome semantic = semanticRequested
                ? semanticIndexService.rank(query, candidates.stream()
                .map(candidate -> new RunbookSemanticIndexService.SemanticDocument(
                        candidate.chunkId(), candidate.searchableText())).toList(), retrievalWindow)
                : null;
        List<RankedChunk> finalRanking;
        String engine;
        String semanticStatus;
        BigDecimal semanticCoverage;
        List<String> warnings = new ArrayList<>();
        if (semantic != null && semantic.usable()) {
            List<ReciprocalRankFusion.FusedDocument> fused = ReciprocalRankFusion.fuse(
                    lexicalRanking.stream().map(item -> item.document().chunkId()).toList(),
                    semantic.scores().stream().map(RunbookSemanticIndexService.SemanticScore::chunkId).toList(),
                    rrfRankConstant, topK);
            Map<Long, Double> semanticScores = new HashMap<>();
            semantic.scores().forEach(item -> semanticScores.put(item.chunkId(), item.score()));
            finalRanking = fused.stream().map(item -> new RankedChunk(item.chunkId(), item.score(),
                    item.lexicalRank(), item.semanticRank(), semanticScores.get(item.chunkId()))).toList();
            engine = ReciprocalRankFusion.ENGINE;
            semanticStatus = semantic.state();
            semanticCoverage = semantic.coverage();
        } else {
            finalRanking = lexicalRanking.stream().limit(topK)
                    .map(item -> new RankedChunk(item.document().chunkId(), item.score(),
                            lexicalRanking.indexOf(item) + 1, null, null)).toList();
            engine = Bm25Retriever.ENGINE;
            semanticStatus = semantic == null ? "NOT_REQUESTED" : semantic.state();
            semanticCoverage = semantic == null ? unrequestedIndexStatus.coverage() : semantic.coverage();
            if (semantic != null && semantic.warning() != null) {
                warnings.add("HYBRID".equals(mode)
                        ? "HYBRID 请求未执行：" + semantic.warning().replace("，AUTO 模式使用 BM25", "")
                        + "；已降级 BM25"
                        : semantic.warning());
            }
        }
        List<SearchResult> results = new ArrayList<>();
        for (RankedChunk item : finalRanking) {
            SearchCandidate candidate = candidatesById.get(item.chunkId());
            if (candidate == null) continue;
            results.add(new SearchResult(candidate.documentId(), candidate.stableKey(), candidate.versionNo(),
                    candidate.resourceType(), candidate.serviceCode(), candidate.title(), candidate.sourceType(),
                    candidate.chunkIndex(), candidate.heading(), clip(candidate.content(), 600),
                    BigDecimal.valueOf(item.score()).setScale(4, RoundingMode.HALF_UP),
                    item.lexicalRank(), item.semanticRank(), decimal(item.semanticScore()),
                    citation(candidate), candidate.publishedAt()));
        }
        Long searchId = sourceType == null ? null : feedbackService.recordSearch(
                query, sourceType, mode, engine, role, semanticStatus, semanticCoverage,
                candidates.size(), topK, (System.nanoTime() - startedNanos) / 1_000_000,
                results, actorId);
        return new SearchResponse(searchId, query, mode, engine, topK, candidates.size(), semanticStatus,
                semanticCoverage, warnings, results);
    }

    public SearchResponse searchForUser(String query, Long userId, int topK) {
        String role = userId == null ? "ON_CALL" : jdbcClient.sql(
                        "SELECT role_code FROM sys_user WHERE id = :id AND status = 'ACTIVE'")
                .param("id", userId).query(String.class).optional().orElse("ON_CALL");
        return searchTracked(query, role, userId, topK, "AUTO", "AGENT");
    }

    @Transactional
    public ImportResult importMarkdown(ImportCommand command, Long actorId) {
        return importContent(command, "MARKDOWN", actorId);
    }

    @Transactional
    public ImportResult importFile(FileImportCommand command, MultipartFile file, Long actorId) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RUNBOOK_FILE_EMPTY", "请选择 Markdown 或 PDF 文件");
        }
        if (file.getSize() > MAX_SOURCE_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "RUNBOOK_FILE_TOO_LARGE", "Runbook 文件不能超过 5 MB");
        }
        String fileName = file.getOriginalFilename() == null ? "runbook" : file.getOriginalFilename();
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RUNBOOK_FILE_READ_FAILED", "无法读取上传文件");
        }
        String sourceType;
        String content;
        if (lowerName.endsWith(".md") || lowerName.endsWith(".markdown")) {
            sourceType = "MARKDOWN";
            content = new String(bytes, StandardCharsets.UTF_8);
        } else if (lowerName.endsWith(".pdf")) {
            sourceType = "PDF";
            content = extractPdf(bytes);
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RUNBOOK_FILE_TYPE_UNSUPPORTED",
                    "仅支持 .md、.markdown 和 .pdf 文件");
        }
        ImportCommand imported = new ImportCommand(command.stableKey(), command.resourceType(),
                command.serviceCode(), command.title(), command.summary(), fileName,
                content, command.allowedRoles());
        return importContent(imported, sourceType, actorId);
    }

    public EvaluationView evaluate(Long actorId) {
        List<EvalJudgment> judgments = jdbcClient.sql("""
                        SELECT case_key, query_text, expected_stable_key, relevance_grade
                        FROM runbook_retrieval_eval_case
                        WHERE enabled = TRUE
                        ORDER BY query_text, expected_stable_key, case_key
                        """)
                .query((rs, rowNum) -> new EvalJudgment(rs.getString("case_key"),
                        rs.getString("query_text"), rs.getString("expected_stable_key"),
                        rs.getInt("relevance_grade")))
                .list();
        if (judgments.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "RUNBOOK_EVAL_DATASET_EMPTY", "Runbook 评测集为空");
        }
        List<EvalQuery> queries = groupEvalQueries(judgments);
        int qrelCount = queries.stream().mapToInt(query -> query.expectedDocuments().size()).sum();
        List<LegacyRunbook> legacyRunbooks = jdbcClient.sql("""
                        SELECT id, symptom_keyword FROM runbook WHERE enabled = TRUE ORDER BY id
                        """)
                .query((rs, rowNum) -> new LegacyRunbook(rs.getLong("id"), rs.getString("symptom_keyword")))
                .list();
        MetricAccumulator baseline = new MetricAccumulator(LEGACY_ENGINE, queries.size());
        MetricAccumulator bm25 = new MetricAccumulator(Bm25Retriever.ENGINE, queries.size());
        MetricAccumulator hybrid = new MetricAccumulator(ReciprocalRankFusion.ENGINE, queries.size());
        RunbookSemanticIndexService.IndexStatus semanticIndex = semanticIndexService.status();
        boolean hybridAvailable = "READY".equals(semanticIndex.state());
        String hybridNote = hybridAvailable ? null : semanticIndex.note();
        for (EvalQuery evalQuery : queries) {
            List<String> baselineResults = legacyRunbooks.stream()
                    .filter(item -> evalQuery.query().toLowerCase(Locale.ROOT)
                            .contains(item.keyword().toLowerCase(Locale.ROOT)))
                    .map(item -> "legacy-runbook-" + item.id())
                    .toList();
            baseline.add(evalQuery, baselineResults, false);

            SearchResponse bm25Response = search(evalQuery.query(), "ON_CALL", 3, "BM25");
            bm25.add(evalQuery, stableKeys(bm25Response), hasTopCitation(evalQuery, bm25Response));
            if (hybridAvailable) {
                SearchResponse hybridResponse = search(evalQuery.query(), "ON_CALL", 3, "HYBRID");
                if (!ReciprocalRankFusion.ENGINE.equals(hybridResponse.engine())) {
                    hybridAvailable = false;
                    hybridNote = hybridResponse.warnings().isEmpty()
                            ? "混合检索评测期间发生降级" : hybridResponse.warnings().get(0);
                } else {
                    hybrid.add(evalQuery, stableKeys(hybridResponse), hasTopCitation(evalQuery, hybridResponse));
                }
            }
        }
        EngineMetric baselineMetric = baseline.metric(true, null);
        EngineMetric bm25Metric = bm25.metric(true, null);
        EngineMetric hybridMetric = hybridAvailable
                ? hybrid.metric(true, null)
                : EngineMetric.unavailable(ReciprocalRankFusion.ENGINE, hybridNote);
        List<EngineMetric> metrics = List.of(baselineMetric, bm25Metric, hybridMetric);
        EngineMetric selected = hybridAvailable ? hybridMetric : bm25Metric;
        String evaluationNote = hybridAvailable
                ? "Hybrid 在完整向量覆盖下完成全部固定样例"
                : "Hybrid 未计分：" + hybridNote;
        String datasetVersion = datasetVersion(queries);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql("""
                        INSERT INTO runbook_retrieval_eval_run(
                          engine, baseline_engine, dataset_version, case_count, judgment_count,
                          baseline_recall_at_3, baseline_mrr, baseline_ndcg_at_3,
                          recall_at_3, mrr, ndcg_at_3, citation_hit_rate,
                          failures_json, comparison_json, created_by)
                        VALUES (:engine, :baselineEngine, :datasetVersion, :caseCount, :judgmentCount,
                          :baselineRecall, :baselineMrr, :baselineNdcg,
                          :recall, :mrr, :ndcg, :citationRate,
                          :failures, :comparison, :createdBy)
                        """)
                .param("engine", selected.engine()).param("datasetVersion", datasetVersion)
                .param("baselineEngine", LEGACY_ENGINE)
                .param("baselineRecall", baselineMetric.recallAt3()).param("baselineMrr", baselineMetric.mrr())
                .param("baselineNdcg", baselineMetric.ndcgAt3())
                .param("caseCount", queries.size()).param("judgmentCount", qrelCount)
                .param("recall", selected.recallAt3()).param("mrr", selected.mrr())
                .param("ndcg", selected.ndcgAt3())
                .param("citationRate", selected.citationHitRate()).param("failures", json(selected.failures()))
                .param("comparison", json(new EvaluationComparison(metrics, semanticIndex, evaluationNote)))
                .param("createdBy", actorId).update(keyHolder, "id");
        long id = requiredKey(keyHolder, "RUNBOOK_EVAL_SAVE_FAILED", "无法保存 Runbook 评测结果");
        auditService.record("RUNBOOK_RETRIEVAL_EVALUATE", "RUNBOOK_EVAL", id,
                "固定集 " + queries.size() + " 个查询 / " + qrelCount + " 个 qrels；基线 "
                        + baselineMetric.recallAt3() + "；选择引擎 " + selected.engine()
                        + " Recall@3 " + selected.recallAt3() + "；NDCG@3 " + selected.ndcgAt3());
        return latestEvaluationById(id);
    }

    public EvaluationView latestEvaluation() {
        Long id = jdbcClient.sql("SELECT MAX(id) FROM runbook_retrieval_eval_run")
                .query(Long.class).optional().orElse(null);
        if (id == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RUNBOOK_EVAL_NOT_FOUND", "尚未执行 Runbook 检索评测");
        }
        return latestEvaluationById(id);
    }

    private ImportResult importContent(ImportCommand raw, String sourceType, Long actorId) {
        String stableKey = normalizeStableKey(raw.stableKey());
        String resourceType = normalizeCode(raw.resourceType(), "resourceType", 32);
        String serviceCode = normalizeOptionalCode(raw.serviceCode());
        String title = requireText(raw.title(), "title", 200);
        String summary = optionalText(raw.summary(), 1_000);
        String sourceName = requireText(raw.sourceName(), "sourceName", 255);
        String content = requireText(raw.markdown(), "markdown", MAX_SOURCE_BYTES);
        List<String> roles = normalizeRoles(raw.allowedRoles());
        List<RunbookTextProcessor.ChunkDraft> chunks = RunbookTextProcessor.chunk(content);
        if (chunks.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RUNBOOK_CONTENT_EMPTY", "Runbook 没有可索引内容");
        }
        String hash = sha256(content);
        Long currentId = jdbcClient.sql("""
                        SELECT id FROM runbook_document
                        WHERE stable_key = :stableKey AND status = 'PUBLISHED' AND content_hash = :hash
                        """)
                .param("stableKey", stableKey).param("hash", hash)
                .query(Long.class).optional().orElse(null);
        if (currentId != null) return new ImportResult(documentById(currentId), true);

        int nextVersion = jdbcClient.sql("""
                        SELECT COALESCE(MAX(version_no), 0) + 1
                        FROM runbook_document WHERE stable_key = :stableKey
                        """)
                .param("stableKey", stableKey).query(Integer.class).single();
        jdbcClient.sql("""
                        UPDATE runbook_document SET status = 'SUPERSEDED'
                        WHERE stable_key = :stableKey AND status = 'PUBLISHED'
                        """)
                .param("stableKey", stableKey).update();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbcClient.sql("""
                            INSERT INTO runbook_document(
                              stable_key, version_no, status, resource_type, service_code, title, summary,
                              source_type, source_name, content_hash, markdown_content, created_by)
                            VALUES (:stableKey, :versionNo, 'PUBLISHED', :resourceType, :serviceCode, :title,
                              :summary, :sourceType, :sourceName, :contentHash, :content, :createdBy)
                            """)
                    .param("stableKey", stableKey).param("versionNo", nextVersion)
                    .param("resourceType", resourceType).param("serviceCode", serviceCode)
                    .param("title", title).param("summary", summary).param("sourceType", sourceType)
                    .param("sourceName", sourceName).param("contentHash", hash)
                    .param("content", content).param("createdBy", actorId).update(keyHolder, "id");
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "RUNBOOK_VERSION_CONFLICT", "Runbook 版本并发冲突，请重试");
        }
        long documentId = requiredKey(keyHolder, "RUNBOOK_IMPORT_FAILED", "无法保存 Runbook 文档");
        for (RunbookTextProcessor.ChunkDraft chunk : chunks) {
            jdbcClient.sql("""
                            INSERT INTO runbook_chunk(document_id, chunk_index, heading, content, char_count)
                            VALUES (:documentId, :chunkIndex, :heading, :content, :charCount)
                            """)
                    .param("documentId", documentId).param("chunkIndex", chunk.index())
                    .param("heading", chunk.heading()).param("content", chunk.content())
                    .param("charCount", chunk.content().length()).update();
        }
        for (String role : roles) {
            jdbcClient.sql("INSERT INTO runbook_document_acl(document_id, role_code) VALUES (:id, :role)")
                    .param("id", documentId).param("role", role).update();
        }
        auditService.record("RUNBOOK_IMPORT", "RUNBOOK", stableKey,
                "导入 " + sourceType + " 版本 v" + nextVersion + "，分块 " + chunks.size() + "，权限 " + roles);
        return new ImportResult(documentById(documentId), false);
    }

    private List<SearchCandidate> loadCandidates(String role) {
        return jdbcClient.sql("""
                        SELECT c.id AS chunk_id, c.chunk_index, c.heading, c.content,
                               d.id AS document_id, d.stable_key, d.version_no, d.resource_type,
                               d.service_code, d.title, d.summary, d.source_type, d.published_at
                        FROM runbook_chunk c
                        JOIN runbook_document d ON d.id = c.document_id
                        JOIN runbook_document_acl a ON a.document_id = d.id AND a.role_code = :role
                        WHERE d.status = 'PUBLISHED'
                        ORDER BY d.stable_key, c.chunk_index
                        """)
                .param("role", role)
                .query((rs, rowNum) -> new SearchCandidate(
                        rs.getLong("chunk_id"), rs.getLong("document_id"), rs.getString("stable_key"),
                        rs.getInt("version_no"), rs.getString("resource_type"), rs.getString("service_code"),
                        rs.getString("title"), rs.getString("summary"), rs.getString("source_type"),
                        rs.getInt("chunk_index"), rs.getString("heading"), rs.getString("content"),
                        rs.getObject("published_at", LocalDateTime.class)))
                .list();
    }

    private DocumentView documentById(long id) {
        return jdbcClient.sql("""
                        SELECT d.id, d.stable_key, d.version_no, d.status, d.resource_type,
                               d.service_code, d.title, d.summary, d.source_type, d.source_name,
                               d.content_hash, d.markdown_content, d.created_by, d.created_at, d.published_at,
                               (SELECT COUNT(*) FROM runbook_chunk c WHERE c.document_id = d.id) AS chunk_count
                        FROM runbook_document d WHERE d.id = :id
                        """)
                .param("id", id)
                .query((rs, rowNum) -> mapDocument(rs.getLong("id"), rs.getString("stable_key"),
                        rs.getInt("version_no"), rs.getString("status"), rs.getString("resource_type"),
                        rs.getString("service_code"), rs.getString("title"), rs.getString("summary"),
                        rs.getString("source_type"), rs.getString("source_name"), rs.getString("content_hash"),
                        rs.getString("markdown_content"), rs.getObject("created_by", Long.class),
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getObject("published_at", LocalDateTime.class), rs.getInt("chunk_count")))
                .single();
    }

    private DocumentView mapDocument(long id, String stableKey, int versionNo, String status,
                                     String resourceType, String serviceCode, String title, String summary,
                                     String sourceType, String sourceName, String contentHash, String markdown,
                                     Long createdBy, LocalDateTime createdAt, LocalDateTime publishedAt,
                                     int chunkCount) {
        List<String> roles = jdbcClient.sql(
                        "SELECT role_code FROM runbook_document_acl WHERE document_id = :id ORDER BY role_code")
                .param("id", id).query(String.class).list();
        return new DocumentView(id, stableKey, versionNo, status, resourceType, serviceCode, title, summary,
                sourceType, sourceName, contentHash, markdown, roles, chunkCount, createdBy, createdAt, publishedAt);
    }

    private EvaluationView latestEvaluationById(long id) {
        return jdbcClient.sql("""
                        SELECT id, engine, baseline_engine, dataset_version, case_count,
                               judgment_count, baseline_recall_at_3, baseline_mrr, baseline_ndcg_at_3,
                               recall_at_3, mrr, ndcg_at_3,
                               citation_hit_rate, failures_json, comparison_json, created_by, created_at
                        FROM runbook_retrieval_eval_run WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, rowNum) -> {
                    String comparisonJson = rs.getString("comparison_json");
                    EvaluationComparison comparison = comparisonJson == null
                            ? legacyComparison(rs.getString("engine"), rs.getString("baseline_engine"),
                            rs.getBigDecimal("baseline_recall_at_3"), rs.getBigDecimal("baseline_mrr"),
                            rs.getBigDecimal("recall_at_3"), rs.getBigDecimal("mrr"),
                            rs.getBigDecimal("citation_hit_rate"))
                            : comparison(comparisonJson);
                    return new EvaluationView(rs.getLong("id"), rs.getString("engine"),
                            rs.getString("baseline_engine"), rs.getString("dataset_version"), rs.getInt("case_count"),
                            rs.getObject("judgment_count", Integer.class) == null
                                    ? rs.getInt("case_count") : rs.getInt("judgment_count"),
                            rs.getBigDecimal("baseline_recall_at_3"), rs.getBigDecimal("baseline_mrr"),
                            rs.getBigDecimal("baseline_ndcg_at_3"), rs.getBigDecimal("recall_at_3"),
                            rs.getBigDecimal("mrr"), rs.getBigDecimal("ndcg_at_3"),
                            rs.getBigDecimal("citation_hit_rate"), rs.getString("failures_json"),
                            comparison.metrics(), comparison.semanticIndex(), comparison.note(),
                            rs.getObject("created_by", Long.class), rs.getObject("created_at", LocalDateTime.class));
                })
                .single();
    }

    private String extractPdf(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "RUNBOOK_PDF_ENCRYPTED", "不支持加密 PDF");
            }
            if (document.getNumberOfPages() > MAX_PDF_PAGES) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "RUNBOOK_PDF_TOO_MANY_PAGES", "PDF 不能超过 200 页");
            }
            return requireText(new PDFTextStripper().getText(document), "pdfText", MAX_SOURCE_BYTES);
        } catch (ApiException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RUNBOOK_PDF_INVALID", "PDF 无法解析或未包含可提取文本");
        }
    }

    private static List<EvalQuery> groupEvalQueries(List<EvalJudgment> judgments) {
        Map<String, Map<String, List<EvalJudgment>>> byQuery = new LinkedHashMap<>();
        for (EvalJudgment judgment : judgments) {
            byQuery.computeIfAbsent(judgment.query(), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(judgment.expectedStableKey(), ignored -> new ArrayList<>())
                    .add(judgment);
        }
        List<EvalQuery> queries = new ArrayList<>();
        byQuery.forEach((query, byDocument) -> {
            List<ExpectedDocument> expectedDocuments = new ArrayList<>();
            byDocument.forEach((stableKey, documentJudgments) -> {
                double averageGrade = documentJudgments.stream()
                        .mapToInt(EvalJudgment::relevanceGrade).average().orElseThrow();
                expectedDocuments.add(new ExpectedDocument(stableKey,
                        BigDecimal.valueOf(averageGrade).setScale(6, RoundingMode.HALF_UP),
                        documentJudgments.stream().map(EvalJudgment::caseKey).sorted().toList()));
            });
            queries.add(new EvalQuery(query, List.copyOf(expectedDocuments)));
        });
        return List.copyOf(queries);
    }

    private String datasetVersion(List<EvalQuery> queries) {
        String documents = jdbcClient.sql("""
                        SELECT stable_key, version_no FROM runbook_document
                        WHERE status = 'PUBLISHED' ORDER BY stable_key
                        """)
                .query((rs, rowNum) -> rs.getString("stable_key") + ":" + rs.getInt("version_no"))
                .list().toString();
        return sha256(queries.toString() + documents).substring(0, 16);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "RUNBOOK_EVAL_SERIALIZATION_FAILED", "Runbook 评测失败样例无法序列化");
        }
    }

    private EvaluationComparison comparison(String value) {
        try {
            return objectMapper.readValue(value, EvaluationComparison.class);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "RUNBOOK_EVAL_DESERIALIZATION_FAILED", "Runbook 评测对照结果无法读取");
        }
    }

    private EvaluationComparison legacyComparison(String engine, String baselineEngine,
                                                  BigDecimal baselineRecall, BigDecimal baselineMrr,
                                                  BigDecimal recall, BigDecimal mrr,
                                                  BigDecimal citationRate) {
        return new EvaluationComparison(List.of(
                new EngineMetric(baselineEngine, true, baselineRecall, baselineMrr,
                        BigDecimal.ZERO, null, List.of(), "V1.6 checkpoint 01 历史结果"),
                new EngineMetric(engine, true, recall, mrr, citationRate,
                        null, List.of(), "V1.6 checkpoint 01 历史结果"),
                EngineMetric.unavailable(ReciprocalRankFusion.ENGINE, "该历史评测尚未接入 Hybrid")),
                semanticIndexService.status(), "由旧评测记录兼容生成");
    }

    private static List<String> stableKeys(SearchResponse response) {
        return response.results().stream().map(SearchResult::stableKey).toList();
    }

    private static boolean hasTopCitation(EvalQuery evalQuery, SearchResponse response) {
        if (response.results().isEmpty()) return false;
        SearchResult top = response.results().get(0);
        return evalQuery.expectedDocuments().stream()
                .anyMatch(expected -> expected.stableKey().equals(top.stableKey()))
                && top.citation().startsWith("runbook:" + top.stableKey() + ":");
    }

    private static String citation(SearchCandidate candidate) {
        return "runbook:" + candidate.stableKey() + ":v" + candidate.versionNo()
                + "#chunk-" + candidate.chunkIndex();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static BigDecimal ratio(int numerator, int denominator) {
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    private static long requiredKey(KeyHolder keyHolder, String code, String message) {
        Number key = keyHolder.getKey();
        if (key == null) throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, code, message);
        return key.longValue();
    }

    private static String normalizeStableKey(String value) {
        String normalized = requireText(value, "stableKey", 80).toLowerCase(Locale.ROOT);
        if (!STABLE_KEY.matcher(normalized).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RUNBOOK_STABLE_KEY_INVALID",
                    "stableKey 仅允许 3-80 位小写字母、数字和连字符");
        }
        return normalized;
    }

    private static String normalizeCode(String value, String field, int maxLength) {
        String normalized = requireText(value, field, maxLength).toUpperCase(Locale.ROOT);
        if (!CODE.matcher(normalized).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RUNBOOK_CODE_INVALID",
                    field + " 仅允许字母、数字、点、下划线和连字符");
        }
        return normalized;
    }

    private static String normalizeOptionalCode(String value) {
        return value == null || value.isBlank() ? null : normalizeCode(value, "serviceCode", 80);
    }

    private static String normalizeRole(String value) {
        String normalized = value == null ? "ON_CALL" : value.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_ROLES.contains(normalized)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "RUNBOOK_ROLE_UNSUPPORTED", "当前角色不能访问 Runbook");
        }
        return normalized;
    }

    private static String normalizeSearchMode(String value) {
        String normalized = value == null ? "AUTO" : value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("AUTO", "BM25", "HYBRID").contains(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RUNBOOK_SEARCH_MODE_INVALID",
                    "mode 仅支持 AUTO、BM25 或 HYBRID");
        }
        return normalized;
    }

    private static List<String> normalizeRoles(List<String> rawRoles) {
        if (rawRoles == null || rawRoles.isEmpty()) return List.copyOf(SUPPORTED_ROLES.stream().sorted().toList());
        LinkedHashSet<String> roles = new LinkedHashSet<>();
        for (String raw : rawRoles) {
            if (raw == null) continue;
            for (String part : raw.split(",")) roles.add(normalizeRole(part));
        }
        if (roles.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RUNBOOK_ACL_EMPTY", "至少指定一个可访问角色");
        }
        return List.copyOf(roles);
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RUNBOOK_FIELD_REQUIRED", field + " 不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RUNBOOK_FIELD_TOO_LONG",
                    field + " 不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }

    private static String optionalText(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        return clip(value.trim(), maxLength);
    }

    private static String clip(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private record SearchCandidate(long chunkId, long documentId, String stableKey, int versionNo,
                                   String resourceType, String serviceCode, String title, String summary,
                                   String sourceType, int chunkIndex, String heading, String content,
                                   LocalDateTime publishedAt) {
        String searchableText() {
            return title + " " + title + " " + resourceType + " "
                    + (serviceCode == null ? "" : serviceCode + " ")
                    + (summary == null ? "" : summary + " ") + heading + " " + heading + " " + content;
        }
    }

    private record RankedChunk(long chunkId, double score, Integer lexicalRank,
                               Integer semanticRank, Double semanticScore) {
    }

    private record EvalJudgment(String caseKey, String query, String expectedStableKey, int relevanceGrade) {
    }

    private record ExpectedDocument(String stableKey, BigDecimal relevanceGrade, List<String> caseKeys) {
    }

    private record EvalQuery(String query, List<ExpectedDocument> expectedDocuments) {
    }

    private record LegacyRunbook(long id, String keyword) {
    }

    public record ImportCommand(String stableKey, String resourceType, String serviceCode,
                                String title, String summary, String sourceName,
                                String markdown, List<String> allowedRoles) {
    }

    public record FileImportCommand(String stableKey, String resourceType, String serviceCode,
                                    String title, String summary, List<String> allowedRoles) {
    }

    public record ImportResult(DocumentView document, boolean reused) {
    }

    public record DocumentView(long id, String stableKey, int versionNo, String status,
                               String resourceType, String serviceCode, String title, String summary,
                               String sourceType, String sourceName, String contentHash, String markdown,
                               List<String> allowedRoles, int chunkCount, Long createdBy,
                               LocalDateTime createdAt, LocalDateTime publishedAt) {
    }

    public record SearchResponse(Long searchId, String query, String requestedMode, String engine, int topK,
                                 int candidateChunkCount, String semanticStatus,
                                 BigDecimal semanticCoverage, List<String> warnings,
                                 List<SearchResult> results) {
    }

    public record SearchResult(long documentId, String stableKey, int versionNo,
                               String resourceType, String serviceCode, String title, String sourceType,
                               int chunkIndex, String heading, String excerpt, BigDecimal score,
                               Integer lexicalRank, Integer semanticRank, BigDecimal semanticScore,
                               String citation, LocalDateTime publishedAt) {
    }

    public record EvaluationFailure(String caseKey, String query, String expectedStableKey,
                                    List<String> expectedStableKeys, Map<String, BigDecimal> expectedGrades,
                                    List<String> returnedStableKeys) {
    }

    public record EngineMetric(String engine, boolean available, BigDecimal recallAt3, BigDecimal mrr,
                               BigDecimal citationHitRate, BigDecimal ndcgAt3,
                               List<EvaluationFailure> failures, String note) {
        static EngineMetric unavailable(String engine, String note) {
            return new EngineMetric(engine, false, null, null, null, null, List.of(), note);
        }
    }

    public record EvaluationComparison(List<EngineMetric> metrics,
                                       RunbookSemanticIndexService.IndexStatus semanticIndex,
                                       String note) {
    }

    public record EvaluationView(long id, String engine, String baselineEngine,
                                 String datasetVersion, int caseCount, int judgmentCount,
                                 BigDecimal baselineRecallAt3, BigDecimal baselineMrr, BigDecimal baselineNdcgAt3,
                                 BigDecimal recallAt3, BigDecimal mrr, BigDecimal ndcgAt3,
                                 BigDecimal citationHitRate,
                                 String failuresJson, List<EngineMetric> metrics,
                                 RunbookSemanticIndexService.IndexStatus semanticIndex,
                                 String evaluationNote, Long createdBy, LocalDateTime createdAt) {
    }

    private static final class MetricAccumulator {
        private final String engine;
        private final int caseCount;
        private double recallSum;
        private int citationHits;
        private double reciprocalRanks;
        private double ndcgSum;
        private final List<EvaluationFailure> failures = new ArrayList<>();

        private MetricAccumulator(String engine, int caseCount) {
            this.engine = engine;
            this.caseCount = caseCount;
        }

        private void add(EvalQuery evalQuery, List<String> rawReturnedStableKeys, boolean topCitation) {
            List<String> returnedStableKeys = rawReturnedStableKeys.stream().distinct().limit(3).toList();
            Map<String, BigDecimal> expectedGrades = new LinkedHashMap<>();
            evalQuery.expectedDocuments().forEach(expected ->
                    expectedGrades.put(expected.stableKey(), expected.relevanceGrade()));
            long recalledDocuments = returnedStableKeys.stream().filter(expectedGrades::containsKey).count();
            recallSum += (double) recalledDocuments / expectedGrades.size();
            int rank = 0;
            for (int index = 0; index < returnedStableKeys.size(); index++) {
                if (expectedGrades.containsKey(returnedStableKeys.get(index))) {
                    rank = index + 1;
                    break;
                }
            }
            if (rank > 0) {
                reciprocalRanks += 1.0 / rank;
                if (rank == 1 && topCitation) citationHits++;
            }
            ndcgSum += ndcgAt3(returnedStableKeys, expectedGrades);
            if (recalledDocuments < expectedGrades.size()) {
                List<String> caseKeys = evalQuery.expectedDocuments().stream()
                        .flatMap(expected -> expected.caseKeys().stream()).sorted().toList();
                List<String> expectedStableKeys = List.copyOf(expectedGrades.keySet());
                failures.add(new EvaluationFailure(caseKeys.get(0), evalQuery.query(),
                        expectedStableKeys.get(0), expectedStableKeys, Map.copyOf(expectedGrades),
                        returnedStableKeys));
            }
        }

        private EngineMetric metric(boolean available, String note) {
            return new EngineMetric(engine, available,
                    BigDecimal.valueOf(recallSum / caseCount).setScale(6, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(reciprocalRanks / caseCount).setScale(6, RoundingMode.HALF_UP),
                    ratio(citationHits, caseCount),
                    BigDecimal.valueOf(ndcgSum / caseCount).setScale(6, RoundingMode.HALF_UP),
                    List.copyOf(failures), note);
        }

        private static double ndcgAt3(List<String> returnedStableKeys,
                                      Map<String, BigDecimal> expectedGrades) {
            double dcg = 0;
            for (int index = 0; index < returnedStableKeys.size() && index < 3; index++) {
                BigDecimal grade = expectedGrades.get(returnedStableKeys.get(index));
                if (grade != null) dcg += gain(grade.doubleValue()) / log2(index + 2);
            }
            List<BigDecimal> idealGrades = expectedGrades.values().stream()
                    .sorted(java.util.Comparator.reverseOrder()).limit(3).toList();
            double idealDcg = 0;
            for (int index = 0; index < idealGrades.size(); index++) {
                idealDcg += gain(idealGrades.get(index).doubleValue()) / log2(index + 2);
            }
            return idealDcg == 0 ? 0 : dcg / idealDcg;
        }

        private static double gain(double relevanceGrade) {
            return Math.pow(2.0, relevanceGrade) - 1.0;
        }

        private static double log2(double value) {
            return Math.log(value) / Math.log(2.0);
        }
    }
}
