package org.trigger.opspilot.runbook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-hybrid-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.ai.dashscope.api-key=disabled",
        "spring.ai.dashscope.embedding.enabled=false",
        "opspilot.ai.enabled=false",
        "opspilot.runbook.semantic.enabled=true",
        "opspilot.runbook.semantic.minimum-coverage=1.0"
})
@AutoConfigureMockMvc
@Transactional
class RunbookHybridRetrievalIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @MockBean
    private EmbeddingModel embeddingModel;

    @Test
    void shouldBuildIdempotentIndexFuseRankingsEvaluateAndPreserveOldIndexOnFailure() throws Exception {
        when(embeddingModel.embed(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream().map(this::vectorFor).toList();
        });
        String adminToken = login("admin", "OpsPilot@2026");

        mockMvc.perform(post("/api/v1/runbooks/semantic-index/rebuild")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reused").value(false))
                .andExpect(jsonPath("$.data.chunkCount").value(6))
                .andExpect(jsonPath("$.data.dimensions").value(6))
                .andExpect(jsonPath("$.data.status.state").value("READY"))
                .andExpect(jsonPath("$.data.status.coverage").value(1.0));

        mockMvc.perform(post("/api/v1/runbooks/semantic-index/rebuild")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reused").value(true));

        mockMvc.perform(get("/api/v1/runbooks/search")
                        .header("Authorization", bearer(adminToken))
                        .queryParam("q", "消息积压 consumer group offset 位点")
                        .queryParam("mode", "HYBRID"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.engine").value("HYBRID_RRF_V1"))
                .andExpect(jsonPath("$.data.semanticStatus").value("READY"))
                .andExpect(jsonPath("$.data.results[0].stableKey").value("kafka-consumer-lag"))
                .andExpect(jsonPath("$.data.results[0].semanticRank").isNumber());

        mockMvc.perform(post("/api/v1/runbooks/evaluations")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.caseCount").value(13))
                .andExpect(jsonPath("$.data.metrics[2].engine").value("HYBRID_RRF_V1"))
                .andExpect(jsonPath("$.data.metrics[2].available").value(true));

        Map<String, Object> newDocument = Map.of(
                "stableKey", "dns-resolution-failure",
                "resourceType", "NETWORK",
                "title", "DNS 解析失败处置",
                "sourceName", "test/dns.md",
                "markdown", "# DNS\n检查 NXDOMAIN、resolver、CoreDNS 与上游 nameserver。",
                "allowedRoles", List.of("ADMIN", "ON_CALL"));
        mockMvc.perform(post("/api/v1/runbooks/imports/markdown")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(newDocument)))
                .andExpect(status().isOk());

        reset(embeddingModel);
        when(embeddingModel.embed(anyList())).thenThrow(new IllegalStateException("provider unavailable"));
        mockMvc.perform(post("/api/v1/runbooks/semantic-index/rebuild")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value(RunbookSemanticIndexService.PROVIDER_FAILURE));

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM runbook_chunk_embedding")
                .query(Integer.class).single()).isEqualTo(6);
        assertThat(jdbcClient.sql("SELECT status FROM runbook_embedding_index_run ORDER BY id DESC LIMIT 1")
                .query(String.class).single()).isEqualTo("FAILED");

        mockMvc.perform(get("/api/v1/runbooks/search")
                        .header("Authorization", bearer(adminToken))
                        .queryParam("q", "DNS NXDOMAIN resolver")
                        .queryParam("mode", "AUTO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.engine").value("BM25_LOCAL_V1"))
                .andExpect(jsonPath("$.data.semanticStatus").value("PARTIAL"))
                .andExpect(jsonPath("$.data.warnings[0]").isNotEmpty())
                .andExpect(jsonPath("$.data.results[0].stableKey").value("dns-resolution-failure"));
    }

    private float[] vectorFor(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        if (normalized.contains("kafka") || normalized.contains("消息积压") || normalized.contains("consumer group")) {
            return new float[]{1, 0, 0, 0, 0, 0};
        }
        if (normalized.contains("mysql") || normalized.contains("数据库连接")) {
            return new float[]{0, 1, 0, 0, 0, 0};
        }
        if (normalized.contains("crashloop") || normalized.contains("pod") || normalized.contains("oomkilled")) {
            return new float[]{0, 0, 1, 0, 0, 0};
        }
        if (normalized.contains("redis") || normalized.contains("缓存客户端") || normalized.contains("连接池")) {
            return new float[]{0, 0, 0, 1, 0, 0};
        }
        if (normalized.contains("token") || normalized.contains("登录凭证") || normalized.contains("认证")) {
            return new float[]{0, 0, 0, 0, 1, 0};
        }
        return new float[]{0, 0, 0, 0, 0, 1};
    }

    private String login(String username, String password) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(response).path("data");
        return data.path("accessToken").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
