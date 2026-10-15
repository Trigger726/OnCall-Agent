package org.trigger.opspilot.runbook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:runbook-citation;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.ai.dashscope.api-key=disabled", "spring.ai.dashscope.embedding.enabled=false",
        "opspilot.ai.enabled=false", "opspilot.runbook.semantic.enabled=false"
})
class RunbookCitationIntegrationTest extends CitationScenarios {}

@AutoConfigureMockMvc
@Transactional
abstract class CitationScenarios {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;

    @BeforeEach void verifiesActualDatabaseProduct() throws Exception {
        try (var connection = dataSource.getConnection()) {
            var metadata = connection.getMetaData();
            String expected = getClass().getSimpleName().startsWith("MySql") ? "MySQL" : "H2";
            assertThat(metadata.getDatabaseProductName()).isEqualTo(expected);
            System.out.printf("CITATION_DATABASE %s|%s|%s%n", getClass().getSimpleName(),
                    metadata.getDatabaseProductName(), metadata.getDatabaseProductVersion());
        }
    }

    @Test void readsExactPersistedChunkWithoutTruncationOrHtmlExecution() throws Exception {
        String content = "原始片段🙂\n" + "保留完整处置细节。".repeat(95) + "\n<script>alert('not executable')</script>";
        long id = submit("citation-long", "# 验证章节\n" + content, List.of("ADMIN", "ON_CALL"));
        approve(id);
        String saved = jdbc.sql("SELECT content FROM runbook_chunk WHERE document_id=:id AND chunk_index=0")
                .param("id", id).query(String.class).single();
        assertThat(saved.length()).isGreaterThan(600);
        mvc.perform(get(url("citation-long", 1, 0)).header("Authorization", bearer("zhangwei")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").value(saved))
                .andExpect(jsonPath("$.data.citation").value("runbook:citation-long:v1#chunk-0"))
                .andExpect(jsonPath("$.data.heading").value("验证章节"))
                .andExpect(jsonPath("$.data.sourceName").value("citation-fixture.md"))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
    }

    @Test void oldCitationsRemainOnOriginalVersionAfterPublishingReplacement() throws Exception {
        approve(submit("citation-version", "# 旧步骤\n版本一原文，不替换为最新内容。", List.of("ADMIN", "ON_CALL")));
        approve(submit("citation-version", "# 新步骤\n版本二不同原文。", List.of("ADMIN", "ON_CALL")));
        mvc.perform(get(url("citation-version", 1, 0)).header("Authorization", bearer("zhangwei")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.versionNo").value(1))
                .andExpect(jsonPath("$.data.status").value("SUPERSEDED"))
                .andExpect(jsonPath("$.data.content").value("版本一原文，不替换为最新内容。"));
        mvc.perform(get(url("citation-version", 2, 0)).header("Authorization", bearer("zhangwei")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").value("版本二不同原文。"));
    }

    @Test void checksEachVersionsCurrentAclAndDoesNotRevealMissingVersusDenied() throws Exception {
        long id = submit("citation-private", "# 私有\n管理员原文。", List.of("ADMIN"));approve(id);
        mvc.perform(get(url("citation-private", 1, 0)).header("Authorization", bearer("admin"))).andExpect(status().isOk());
        String denied = missing("citation-private", 1, 0, "zhangwei");
        assertThat(denied).isEqualTo(missing("citation-absent", 1, 0, "zhangwei"));
        jdbc.sql("INSERT INTO runbook_document_acl(document_id,role_code) VALUES (:id,'ON_CALL')").param("id", id).update();
        mvc.perform(get(url("citation-private", 1, 0)).header("Authorization", bearer("zhangwei"))).andExpect(status().isOk());
        jdbc.sql("DELETE FROM runbook_document_acl WHERE document_id=:id AND role_code='ON_CALL'").param("id", id).update();
        missing("citation-private", 1, 0, "zhangwei");
    }

    @Test void draftRejectedAndUnknownChunksAreNotPublishedSources() throws Exception {
        long id = submit("citation-draft", "# 未发布\n禁止作为来源。", List.of("ADMIN", "ON_CALL"));
        missing("citation-draft", 1, 0, "admin");
        jdbc.sql("UPDATE runbook_document SET status='REJECTED' WHERE id=:id").param("id", id).update();
        missing("citation-draft", 1, 0, "admin");
        approve(submit("citation-live", "# 已发布\n可读。", List.of("ADMIN", "ON_CALL")));
        missing("citation-live", 1, 999, "admin");missing("citation-live", 999, 0, "admin");
    }

    @Test void requiresAuthenticationSupportedRoleAndValidCoordinates() throws Exception {
        mvc.perform(get(url("redis-connection-pool", 1, 0))).andExpect(status().isUnauthorized());
        mvc.perform(get(url("redis-connection-pool", 1, 0)).header("Authorization", bearer("auditor"))).andExpect(status().isForbidden());
        for (String path : List.of(url("valid-key", 0, 0), url("valid-key", 1, -1), url("bad_key", 1, 0)))
            mvc.perform(get(path).header("Authorization", bearer("admin"))).andExpect(status().isBadRequest());
    }

    @Test void rechecksCurrentRoleAndRevokedAccountUsingTheSamePreviouslyValidToken() throws Exception {
        approve(submit("citation-session", "# 会话\n只能由当前获授权账号读取。", List.of("ADMIN", "ON_CALL")));
        String token = bearer("zhangwei");
        mvc.perform(get(url("citation-session", 1, 0)).header("Authorization", token)).andExpect(status().isOk());
        jdbc.sql("UPDATE sys_user SET role_code='AUDITOR' WHERE username='zhangwei'").update();
        mvc.perform(get(url("citation-session", 1, 0)).header("Authorization", token)).andExpect(status().isForbidden());
        jdbc.sql("UPDATE sys_user SET role_code='ON_CALL',auth_version=auth_version+1 WHERE username='zhangwei'").update();
        mvc.perform(get(url("citation-session", 1, 0)).header("Authorization", token)).andExpect(status().isUnauthorized());
    }

    @Test void keepsFencedCodeCommentsInTheReferencedSourceChunk() throws Exception {
        String body = "READERPROBE\n```sh\n# 注释不是章节\n  echo safe\n```\n完成";
        long id = submit("citation-code", "# 真正章节\n" + body, List.of("ADMIN", "ON_CALL"));approve(id);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM runbook_chunk WHERE document_id=:id").param("id", id)
                .query(Integer.class).single()).isEqualTo(1);
        mvc.perform(get(url("citation-code", 1, 0)).header("Authorization", bearer("zhangwei")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").value(body))
                .andExpect(jsonPath("$.data.heading").value("真正章节"));
    }

    private long submit(String key, String markdown, List<String> roles) throws Exception {
        JsonNode result = json.readTree(mvc.perform(post("/api/v1/runbooks/imports/markdown")
                .header("Authorization", bearer("admin")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("stableKey", key, "resourceType", "CACHE", "title", "引用测试手册",
                        "sourceName", "citation-fixture.md", "markdown", markdown, "allowedRoles", roles))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return result.at("/data/document/id").asLong();
    }
    private void approve(long id) throws Exception {
        mvc.perform(post("/api/v1/runbooks/publications/"+id+"/decisions").header("Authorization", bearer("lina"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("expectedVersion", 0,
                        "decision", "APPROVE", "requestKey", UUID.randomUUID().toString(), "reason", "独立复核引用原文"))))
                .andExpect(status().isOk());
    }
    private String missing(String key, int version, int chunk, String user) throws Exception {
        return json.readTree(mvc.perform(get(url(key, version, chunk)).header("Authorization", bearer(user)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("RUNBOOK_CITATION_NOT_FOUND"))
                .andExpect(jsonPath("$.data").isEmpty()).andReturn().getResponse().getContentAsString()).path("error").toString();
    }
    private String bearer(String user) throws Exception {
        return "Bearer " + json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("username", user, "password", "OpsPilot@2026"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).at("/data/accessToken").asText();
    }
    private static String url(String key, int version, int chunk) {return "/api/v1/runbooks/"+key+"/versions/"+version+"/chunks/"+chunk;}
}
