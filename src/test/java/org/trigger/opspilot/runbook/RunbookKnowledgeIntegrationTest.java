package org.trigger.opspilot.runbook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-runbook-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.ai.dashscope.api-key=disabled",
        "opspilot.ai.enabled=false"
})
@AutoConfigureMockMvc
@Transactional
class RunbookKnowledgeIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void shouldSearchMigratedRunbooksAndPersistDeterministicEvaluation() throws Exception {
        String onCallToken = login("zhangwei", "OpsPilot@2026");
        String adminToken = login("admin", "OpsPilot@2026");

        mockMvc.perform(get("/api/v1/runbooks").header("Authorization", bearer(onCallToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(6))
                .andExpect(jsonPath("$.data[0].versionNo").value(1));

        mockMvc.perform(get("/api/v1/runbooks/semantic-index")
                        .header("Authorization", bearer(onCallToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("DISABLED"))
                .andExpect(jsonPath("$.data.indexedChunkCount").value(0));

        mockMvc.perform(get("/api/v1/runbooks/search")
                        .header("Authorization", bearer(onCallToken))
                        .queryParam("q", "Redis 连接池 active pending 慢命令")
                        .queryParam("topK", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.engine").value("BM25_LOCAL_V1"))
                .andExpect(jsonPath("$.data.semanticStatus").value("DISABLED"))
                .andExpect(jsonPath("$.data.warnings[0]").isNotEmpty())
                .andExpect(jsonPath("$.data.results[0].stableKey").value("legacy-runbook-2"))
                .andExpect(jsonPath("$.data.results[0].citation")
                        .value("runbook:legacy-runbook-2:v1#chunk-0"));

        mockMvc.perform(get("/api/v1/runbooks/search")
                        .header("Authorization", bearer(onCallToken))
                        .queryParam("q", "Redis pending")
                        .queryParam("mode", "BM25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.semanticStatus").value("NOT_REQUESTED"))
                .andExpect(jsonPath("$.data.warnings.length()").value(0));

        mockMvc.perform(post("/api/v1/runbooks/semantic-index/rebuild")
                        .header("Authorization", bearer(onCallToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/runbooks/semantic-index/rebuild")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RUNBOOK_SEMANTIC_DISABLED"));

        String evaluation = mockMvc.perform(post("/api/v1/runbooks/evaluations")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.caseCount").value(13))
                .andExpect(jsonPath("$.data.recallAt3").value(1.0))
                .andExpect(jsonPath("$.data.mrr").value(0.961538))
                .andExpect(jsonPath("$.data.baselineEngine").value("LEGACY_CONTAINS_V1"))
                .andExpect(jsonPath("$.data.baselineRecallAt3").value(0.0))
                .andExpect(jsonPath("$.data.baselineMrr").value(0.0))
                .andExpect(jsonPath("$.data.baselineNdcgAt3").value(0.0))
                .andExpect(jsonPath("$.data.citationHitRate").value(0.923077))
                .andExpect(jsonPath("$.data.ndcgAt3").value(0.97161))
                .andExpect(jsonPath("$.data.metrics[1].engine").value("BM25_LOCAL_V1"))
                .andExpect(jsonPath("$.data.metrics[1].ndcgAt3").value(0.97161))
                .andExpect(jsonPath("$.data.metrics[2].available").value(false))
                .andReturn().getResponse().getContentAsString();
        long evaluationId = objectMapper.readTree(evaluation).path("data").path("id").asLong();

        mockMvc.perform(get("/api/v1/runbooks/evaluations/latest")
                        .header("Authorization", bearer(onCallToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(evaluationId));
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM runbook_retrieval_eval_run")
                .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void shouldCreateImmutableMarkdownVersionsAndEnforceDocumentAcl() throws Exception {
        String adminToken = login("admin", "OpsPilot@2026");
        String onCallToken = login("zhangwei", "OpsPilot@2026");
        String managerToken = login("lina", "OpsPilot@2026");
        Map<String, Object> versionOne = Map.of(
                "stableKey", "payment-gateway-timeout",
                "resourceType", "APPLICATION",
                "serviceCode", "PAYMENT-GATEWAY",
                "title", "支付网关超时恢复",
                "summary", "支付链路超时的诊断与回滚",
                "sourceName", "docs/runbooks/payment-timeout.md",
                "markdown", "# 诊断\n检查 payment gateway P99 与 upstream timeout。\n# 恢复\n回滚连接池配置并观察十五分钟。",
                "allowedRoles", List.of("ADMIN", "OPS_MANAGER"));

        mockMvc.perform(post("/api/v1/runbooks/imports/markdown")
                        .header("Authorization", bearer(onCallToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(versionOne)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/runbooks/imports/markdown")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(versionOne)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reused").value(false))
                .andExpect(jsonPath("$.data.document.versionNo").value(1))
                .andExpect(jsonPath("$.data.document.chunkCount").value(2));

        mockMvc.perform(post("/api/v1/runbooks/imports/markdown")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(versionOne)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reused").value(true))
                .andExpect(jsonPath("$.data.document.versionNo").value(1));

        String onCallSearch = mockMvc.perform(get("/api/v1/runbooks/search")
                        .header("Authorization", bearer(onCallToken))
                        .queryParam("q", "payment gateway upstream timeout"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(stableKeys(onCallSearch)).doesNotContain("payment-gateway-timeout");

        mockMvc.perform(get("/api/v1/runbooks/search")
                        .header("Authorization", bearer(managerToken))
                        .queryParam("q", "payment gateway upstream timeout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].stableKey").value("payment-gateway-timeout"));

        Map<String, Object> versionTwo = Map.of(
                "stableKey", "payment-gateway-timeout",
                "resourceType", "APPLICATION",
                "serviceCode", "PAYMENT-GATEWAY",
                "title", "支付网关超时恢复",
                "summary", "增加熔断器检查后的第二版",
                "sourceName", "docs/runbooks/payment-timeout.md",
                "markdown", "# 诊断\n检查 payment gateway P99、upstream timeout 与 circuit breaker。\n# 恢复\n回滚连接池配置并观察十五分钟。",
                "allowedRoles", List.of("ADMIN", "OPS_MANAGER"));
        mockMvc.perform(post("/api/v1/runbooks/imports/markdown")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(versionTwo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reused").value(false))
                .andExpect(jsonPath("$.data.document.versionNo").value(2));

        mockMvc.perform(get("/api/v1/runbooks/payment-gateway-timeout/versions")
                        .header("Authorization", bearer(managerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data[1].status").value("SUPERSEDED"));
    }

    @Test
    void shouldExtractAndIndexPdfText() throws Exception {
        String adminToken = login("admin", "OpsPilot@2026");
        MockMultipartFile pdf = new MockMultipartFile(
                "file", "kafka-lag.pdf", MediaType.APPLICATION_PDF_VALUE, pdfBytes());

        mockMvc.perform(multipart("/api/v1/runbooks/imports/file")
                        .file(pdf)
                        .param("stableKey", "kafka-consumer-lag")
                        .param("resourceType", "MIDDLEWARE")
                        .param("serviceCode", "KAFKA-ORDER")
                        .param("title", "Kafka Consumer Lag Recovery")
                        .param("summary", "Consumer lag diagnostics")
                        .param("allowedRoles", "ADMIN,OPS_MANAGER,ON_CALL")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.document.sourceType").value("PDF"))
                .andExpect(jsonPath("$.data.document.chunkCount").value(1));

        mockMvc.perform(get("/api/v1/runbooks/search")
                        .header("Authorization", bearer(adminToken))
                        .queryParam("q", "Kafka consumer lag partition rebalance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].stableKey").value("kafka-consumer-lag"))
                .andExpect(jsonPath("$.data.results[0].sourceType").value("PDF"));
    }

    @Test
    void shouldRejectUnsupportedRunbookFileType() throws Exception {
        String adminToken = login("admin", "OpsPilot@2026");
        MockMultipartFile text = new MockMultipartFile(
                "file", "notes.txt", MediaType.TEXT_PLAIN_VALUE, "plain text".getBytes());

        mockMvc.perform(multipart("/api/v1/runbooks/imports/file")
                        .file(text)
                        .param("stableKey", "unsupported-file")
                        .param("resourceType", "APPLICATION")
                        .param("title", "Unsupported file")
                        .param("allowedRoles", "ADMIN")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("RUNBOOK_FILE_TYPE_UNSUPPORTED"));
    }

    private byte[] pdfBytes() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText("Kafka consumer lag recovery: inspect partition rebalance and offset commits.");
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private List<String> stableKeys(String response) throws Exception {
        List<String> keys = new java.util.ArrayList<>();
        for (JsonNode result : objectMapper.readTree(response).path("data").path("results")) {
            keys.add(result.path("stableKey").asText());
        }
        return keys;
    }

    private String login(String username, String password) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("accessToken").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
