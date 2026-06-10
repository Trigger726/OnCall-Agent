package org.example.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.RerankService;
import org.example.service.VectorSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 内部文档查询工具
 * 使用 RAG (Retrieval-Augmented Generation) 从内部知识库检索相关文档
 * 检索流程：向量召回 → 重排序(Rerank) → 返回结果
 */
@Component
public class InternalDocsTools {

    private static final Logger logger = LoggerFactory.getLogger(InternalDocsTools.class);

    /** 工具名常量，用于动态构建提示词 */
    public static final String TOOL_QUERY_INTERNAL_DOCS = "queryInternalDocs";

    private final VectorSearchService vectorSearchService;
    private final RerankService rerankService;

    @Value("${rag.top-k:3}")
    private int topK = 3; // 默认值

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 构造函数注入依赖
     * Spring 会自动注入 VectorSearchService 和 RerankService
     */
    @Autowired
    public InternalDocsTools(VectorSearchService vectorSearchService, RerankService rerankService) {
        this.vectorSearchService = vectorSearchService;
        this.rerankService = rerankService;
    }
    
    /**
     * 查询内部文档工具
     *
     * @param query 搜索查询，描述您要查找的信息
     * @return JSON 格式的搜索结果，包含相关文档内容、相似度分数和元数据
     */
    @Tool(description = "Use this tool to search internal documentation and knowledge base for relevant information. " +
            "It performs RAG (Retrieval-Augmented Generation) to find similar documents and extract processing steps. " +
            "This is useful when you need to understand internal procedures, best practices, or step-by-step guides " +
            "stored in the company's documentation.")
    public String queryInternalDocs(
            @ToolParam(description = "Search query describing what information you are looking for") 
            String query) {
        

        try {
            // 步骤 1：向量召回 — 从 Milvus 检索 topK 个相似文档
            List<VectorSearchService.SearchResult> searchResults =
                    vectorSearchService.searchSimilarDocuments(query, topK);

            if (searchResults.isEmpty()) {
                return "{\"status\": \"no_results\", \"message\": \"No relevant documents found in the knowledge base.\"}";
            }

            logger.info("向量召回完成，获取到 {} 个候选文档，开始重排序...", searchResults.size());

            // 步骤 2：重排序 — 使用阿里云百炼 gte-rerank 模型对召回结果进行语义重排
            // 注意：rerankSearchResults 内部会校验 rerank.enabled 开关
            List<VectorSearchService.SearchResult> rerankedResults =
                    rerankService.rerankSearchResults(query, searchResults);

            logger.info("重排序完成，最终返回 {} 个文档", rerankedResults.size());

            // 将重排后的结果转换为 JSON 格式
            String resultJson = objectMapper.writeValueAsString(rerankedResults);

            return resultJson;

        } catch (Exception e) {
            logger.error("[工具错误] queryInternalDocs 执行失败", e);
            return String.format("{\"status\": \"error\", \"message\": \"Failed to query internal docs: %s\"}",
                    e.getMessage());
        }
    }
}
