package org.trigger.opspilot.incident;

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
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:incident-query;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "spring.ai.dashscope.api-key=disabled", "opspilot.ai.enabled=false"})
class IncidentQueryIntegrationTest extends IncidentQueryScenarios {}

@AutoConfigureMockMvc
@Transactional
abstract class IncidentQueryScenarios {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired IncidentService service;
    String token, prefix;
    long resource;
    @BeforeEach void setup() throws Exception {
        prefix="查询-"+UUID.randomUUID();
        resource=jdbc.sql("SELECT id FROM cmdb_resource ORDER BY id LIMIT 1").query(Long.class).single();
        var login=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"zhangwei\",\"password\":\"OpsPilot@2026\"}")).andExpect(status().isOk()).andReturn();
        token=json.readTree(login.getResponse().getContentAsString()).path("data").path("accessToken").asText();
    }
    long insert(String suffix,String severity,String status) {
        String code="QUERY-"+UUID.randomUUID();
        jdbc.sql("INSERT INTO incident (incident_code,title,description,severity,status,service_resource_id,version,created_at,updated_at) VALUES (:code,:title,'query fixture',:severity,:status,:resource,0,:time,:time)")
                .param("code",code).param("title",prefix+suffix).param("severity",severity).param("status",status).param("resource",resource).param("time",LocalDateTime.of(2026,10,26,10,0)).update();
        return jdbc.sql("SELECT id FROM incident WHERE incident_code=:code").param("code",code).query(Long.class).single();
    }
    JsonNode query(String q,int page,int size) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/incidents").header("Authorization","Bearer "+token).param("q",q).param("page",""+page).param("size",""+size)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    @Test void searchesTitleCodeAndResourceNameAcrossAllRecords() throws Exception {
        long id=insert("连接池", "P3", "OPEN");
        assertThat(query(prefix+"连接池",1,20).path("total").asInt()).isEqualTo(1);
        String code=jdbc.sql("SELECT incident_code FROM incident WHERE id=:id").param("id",id).query(String.class).single();
        assertThat(query(code.toLowerCase(Locale.ROOT),1,20).path("items").get(0).path("id").asLong()).isEqualTo(id);
        String name=jdbc.sql("SELECT name FROM cmdb_resource WHERE id=:id").param("id",resource).query(String.class).single();
        assertThat(query(name,1,100).path("items").toString()).contains(code);
    }
    @Test void treatsWildcardsQuotesAndInjectionAsLiteralText() throws Exception {
        insert(" %_ O'Reilly", "P3", "OPEN");
        assertThat(query("%_ O'Reilly",1,20).path("total").asInt()).isEqualTo(1);
        assertThat(query("' OR 1=1 --",1,20).path("total").asInt()).isZero();
    }
    @Test void pagesBeyondOneHundredWithoutDuplicatesForTiedTimestamps() throws Exception {
        var expected=new ArrayList<Long>();for(int i=0;i<105;i++)expected.add(insert("-"+i,"P3","OPEN"));
        expected.sort(Comparator.reverseOrder());var actual=new ArrayList<Long>();
        for(int p=1;p<=6;p++){var result=query(prefix,p,20);assertThat(result.path("total").asInt()).isEqualTo(105);for(var row:result.path("items"))actual.add(row.path("id").asLong());}
        assertThat(actual).containsExactlyElementsOf(expected);
    }
    @Test void combinesKeywordStatusAndSeverityWithMatchingCount() {
        long match=insert(" one","P1","OPEN");insert(" two","P1","CLOSED");insert(" three","P3","OPEN");
        var result=service.list("OPEN","P1",prefix,1,20);assertThat(result.total()).isEqualTo(1);assertThat(result.items().get(0).id()).isEqualTo(match);
    }
    @Test void preservesLegacyCallsAndBoundsPageSizeAndLargeOffset() {
        assertThat(service.list(null,null,1,20).total()).isEqualTo(service.list(null,null,"   ",1,20).total());
        assertThat(service.list(null,null,prefix,-1,1000).size()).isEqualTo(100);
        assertThat(service.list(null,null,prefix,Integer.MAX_VALUE,100).items()).isEmpty();
    }
    @Test void rejectsOversizedQueryAndRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/incidents").header("Authorization","Bearer "+token).param("q","x".repeat(121))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/incidents").param("q",prefix)).andExpect(status().isUnauthorized());
    }
}
