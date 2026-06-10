package org.example.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 重排服务
 * 使用阿里云百炼 DashScope Rerank API（gte-rerank 模型）
 * 在向量检索召回后，对文档进行语义相关性重排序，提升检索精度
 *
 * API 文档：https://help.aliyun.com/zh/model-studio/reranker
 */
@Service
public class RerankService {

    private static final Logger logger = LoggerFactory.getLogger(RerankService.class);

    /** DashScope Rerank API 地址 */
    private static final String RERANK_API_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    @Value("${dashscope.api.key}")
    private String apiKey;

    /** 重排模型 */
    @Value("${rerank.model:gte-rerank}")
    private String model;

    /** 重排后返回的最大文档数 */
    @Value("${rerank.top-n:3}")
    private int topN;

    /** 是否启用重排 */
    @Value("${rerank.enabled:true}")
    private boolean enabled;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public RerankService() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    public void init() {
        logger.info("重排服务初始化完成 — enabled: {}, model: {}, topN: {}", enabled, model, topN);
        if (!enabled) {
            logger.warn("⚠️ 重排服务已禁用，检索结果将不经过重排序直接返回");
        }
    }

    /**
     * 对检索召回的文档进行语义重排序
     *
     * @param query     用户原始查询
     * @param documents 召回的文档内容列表（按原始顺序）
     * @return 重排后的文档索引列表，按相关性从高到低排列
     *         每个 Map 包含: index (原始位置), relevance_score (相关性分数)
     * @throws RuntimeException 重排失败时直接抛出异常（不降级）
     */
    public List<RerankResult> rerank(String query, List<String> documents) {
        if (!enabled) {
            logger.info("重排服务已禁用，跳过重排");
            return buildFallbackResults(documents);
        }

        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("查询文本不能为空");
        }

        if (documents == null || documents.isEmpty()) {
            logger.warn("文档列表为空，跳过重排");
            return Collections.emptyList();
        }

        try {
            logger.info("开始调用 DashScope Rerank API — query: {}, documents count: {}, model: {}, topN: {}",
                    query, documents.size(), model, topN);

            // 构建请求体
            Map<String, Object> requestBody = buildRequestBody(query, documents);

            // 设置请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

            // 调用 API
            ResponseEntity<String> response = restTemplate.exchange(
                    RERANK_API_URL,
                    HttpMethod.POST,
                    requestEntity,
                    String.class
            );

            if (response.getStatusCode() != HttpStatus.OK) {
                throw new RuntimeException(
                        "重排 API 返回非 200 状态码: " + response.getStatusCode() + ", body: " + response.getBody());
            }

            // 解析响应
            RerankResponse rerankResponse = objectMapper.readValue(
                    response.getBody(), RerankResponse.class);

            List<RerankResult> results = rerankResponse.getOutput().getResults();

            logger.info("重排完成 — 返回 {} 个结果", results.size());
            for (int i = 0; i < results.size(); i++) {
                RerankResult r = results.get(i);
                logger.info("  #{}: index={}, relevance_score={}",
                        i + 1, r.getIndex(), r.getRelevanceScore());
            }

            return results;

        } catch (Exception e) {
            // 不降级：直接抛出异常
            logger.error("重排 API 调用失败，不降级，直接抛出异常", e);
            throw new RuntimeException("重排失败: " + e.getMessage(), e);
        }
    }

    /**
     * 对 VectorSearchService.SearchResult 列表进行重排
     * 将重排结果直接映射回原始的 SearchResult 对象，并按新顺序排列
     *
     * @param query         用户原始查询
     * @param searchResults Milvus 召回的搜索结果列表
     * @return 重排后的搜索结果列表（按语义相关性从高到低排列），长度 ≤ topN
     */
    public List<VectorSearchService.SearchResult> rerankSearchResults(
            String query, List<VectorSearchService.SearchResult> searchResults) {

        if (!enabled || searchResults.isEmpty()) {
            return searchResults;
        }

        // 提取文档内容
        List<String> documents = searchResults.stream()
                .map(VectorSearchService.SearchResult::getContent)
                .collect(Collectors.toList());

        // 调用重排 API
        List<RerankResult> rerankResults = rerank(query, documents);

        // 按重排结果重新排列
        List<VectorSearchService.SearchResult> reorderedResults = new ArrayList<>();
        for (RerankResult rr : rerankResults) {
            int originalIndex = rr.getIndex();
            if (originalIndex >= 0 && originalIndex < searchResults.size()) {
                VectorSearchService.SearchResult original = searchResults.get(originalIndex);
                // 用重排的相关性分数替换原始的 L2 距离分数
                original.setScore(rr.getRelevanceScore());
                reorderedResults.add(original);
            }
        }

        logger.info("重排完成 — 从 {} 个召回到 {} 个重排结果",
                searchResults.size(), reorderedResults.size());

        return reorderedResults;
    }

    /**
     * 构建 DashScope Rerank API 请求体
     */
    private Map<String, Object> buildRequestBody(String query, List<String> documents) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", query);
        input.put("documents", documents);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("top_n", Math.min(topN, documents.size()));
        parameters.put("return_documents", false);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", input);
        body.put("parameters", parameters);

        return body;
    }

    /**
     * 构建降级结果（重排禁用时的默认顺序）
     */
    private List<RerankResult> buildFallbackResults(List<String> documents) {
        List<RerankResult> results = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            RerankResult result = new RerankResult();
            result.setIndex(i);
            result.setRelevanceScore(1.0f - (i * 0.1f)); // 保持原顺序，分数递减
            results.add(result);
        }
        return results;
    }

    // ==================== API 响应数据结构 ====================

    /**
     * DashScope Rerank API 完整响应
     */
    @Setter
    @Getter
    public static class RerankResponse {
        @JsonProperty("request_id")
        private String requestId;
        private RerankOutput output;
        private RerankUsage usage;
    }

    @Setter
    @Getter
    public static class RerankOutput {
        private List<RerankResult> results;
    }

    @Setter
    @Getter
    public static class RerankResult {
        /** 原始文档列表中的索引位置 */
        private int index;

        /** 语义相关性分数，越高越相关 */
        @JsonProperty("relevance_score")
        private float relevanceScore;
    }

    @Setter
    @Getter
    public static class RerankUsage {
        @JsonProperty("total_tokens")
        private int totalTokens;
    }
}