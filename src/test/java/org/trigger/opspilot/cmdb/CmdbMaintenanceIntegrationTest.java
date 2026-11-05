package org.trigger.opspilot.cmdb;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.trigger.opspilot.audit.AuditService;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:cmdb-maintenance;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","opspilot.ai.enabled=false","spring.ai.dashscope.api-key=disabled","opspilot.oncall.rotation.enabled=false","opspilot.oncall.escalation.enabled=false","opspilot.agent.recovery.enabled=false"})
class CmdbMaintenanceIntegrationTest extends CmdbMaintenanceScenarios { }

@AutoConfigureMockMvc
abstract class CmdbMaintenanceScenarios {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @SpyBean AuditService audit;
    String token, prefix;
    @BeforeEach void setup() throws Exception { token=login("admin");prefix=("CMDB-"+UUID.randomUUID()).toUpperCase(Locale.ROOT); }
    String login(String name)throws Exception{return json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username",name,"password","OpsPilot@2026")))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("accessToken").asText();}
    Map<String,Object> command(String code){return new HashMap<>(Map.of("resourceCode",code,"resourceType","APPLICATION","name","服务 "+code,"environment","PRODUCTION","status","RUNNING","ownerId",2,"description","实际值班服务"));}
    JsonNode create(Map<String,Object> data)throws Exception{return json.readTree(mvc.perform(post("/api/v1/cmdb/resources").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(data))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("resource");}
    long count(String table){return jdbc.sql("SELECT COUNT(*) FROM "+table).query(Long.class).single();}
    void putStatus(long id,Map<String,Object> data,int status)throws Exception{mvc.perform(put("/api/v1/cmdb/resources/"+id).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(data))).andExpect(status().is(status));}
    @Test void managersCreateNormalizedUniqueResourceAndActorAudit()throws Exception{
        token=login("lina");var resource=create(command(prefix.toLowerCase(Locale.ROOT)));assertThat(resource.path("resourceCode").asText()).isEqualTo(prefix);assertThat(resource.path("version").asInt()).isZero();
        var actor=jdbc.sql("SELECT actor_id FROM audit_log WHERE action='RESOURCE_CREATED' AND target_id=:id").param("id",resource.path("id").asText()).query(Long.class).single();assertThat(actor).isEqualTo(jdbc.sql("SELECT id FROM sys_user WHERE username='lina'").query(Long.class).single());
        mvc.perform(post("/api/v1/cmdb/resources").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(command(prefix)))).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("RESOURCE_CODE_EXISTS"));
    }
    @Test void editRejectsStaleVersionAndImmutableCode()throws Exception{
        var data=command(prefix);long id=create(data).path("id").asLong();data.put("version",0);data.put("name","维护后的服务");putStatus(id,data,200);data.put("name","过时覆盖");putStatus(id,data,409);data.put("version",1);data.put("resourceCode",prefix+"-OTHER");putStatus(id,data,400);
        assertThat(jdbc.sql("SELECT name FROM cmdb_resource WHERE id=:id").param("id",id).query(String.class).single()).isEqualTo("维护后的服务");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action='RESOURCE_UPDATED' AND target_id=:id").param("id",Long.toString(id)).query(Long.class).single()).isEqualTo(1);
    }
    @Test void unauthorizedAndReadOnlyRolesCannotWrite()throws Exception{
        var data=json.writeValueAsString(command(prefix));long before=count("cmdb_resource");
        mvc.perform(post("/api/v1/cmdb/resources").contentType(MediaType.APPLICATION_JSON).content(data)).andExpect(status().isUnauthorized());
        for(String user:List.of("zhangwei","auditor")){String read=login(user);mvc.perform(post("/api/v1/cmdb/resources").header("Authorization","Bearer "+read).contentType(MediaType.APPLICATION_JSON).content(data)).andExpect(status().isForbidden());}
        assertThat(count("cmdb_resource")).isEqualTo(before);
    }
    @Test void invalidInputsAndInactiveOrAuditOwnersDoNotWrite()throws Exception{
        long before=count("cmdb_resource");
        for(var change:List.of(Map.entry("name"," "),Map.entry("resourceCode","a/b"),Map.entry("environment","UNKNOWN"),Map.entry("status","UNKNOWN"),Map.entry("description","x".repeat(501)))){
            var data=command(prefix);data.put(change.getKey(),change.getValue());mvc.perform(post("/api/v1/cmdb/resources").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(data))).andExpect(status().isBadRequest());
        }
        var data=command(prefix);data.put("ownerId",jdbc.sql("SELECT id FROM sys_user WHERE username='auditor'").query(Long.class).single());
        mvc.perform(post("/api/v1/cmdb/resources").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(data))).andExpect(status().isBadRequest());
        jdbc.sql("UPDATE sys_user SET status='DISABLED' WHERE id=2").update();try{data.put("ownerId",2);mvc.perform(post("/api/v1/cmdb/resources").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(data))).andExpect(status().isBadRequest());}finally{jdbc.sql("UPDATE sys_user SET status='ACTIVE' WHERE id=2").update();}
        assertThat(count("cmdb_resource")).isEqualTo(before);
    }
    @Test void auditFailureRollsBackResource()throws Exception{
        long before=count("cmdb_resource"),beforeAudit=count("audit_log");doThrow(new IllegalStateException("Injected CMDB audit failure")).when(audit).record(eq("RESOURCE_CREATED"),eq("CMDB_RESOURCE"),any(),anyString());
        mvc.perform(post("/api/v1/cmdb/resources").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(command(prefix)))).andExpect(status().isInternalServerError());assertThat(count("cmdb_resource")).isEqualTo(before);assertThat(count("audit_log")).isEqualTo(beforeAudit);
    }
    @Test void searchFindsRecordsBeyondOneHundredAndTreatsWildcardsLiterally()throws Exception{
        for(int i=0;i<105;i++)jdbc.sql("INSERT INTO cmdb_resource(resource_code,resource_type,name,environment,status) VALUES (:code,'APPLICATION',:name,'TEST','RUNNING')").param("code",prefix+"-"+i).param("name",prefix+" %_ "+i).update();
        var r=json.readTree(mvc.perform(get("/api/v1/cmdb/resources").header("Authorization","Bearer "+token).param("q",prefix+" %_").param("page","6").param("size","20")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");assertThat(r.path("total").asInt()).isEqualTo(105);assertThat(r.path("items").size()).isEqualTo(5);
        mvc.perform(get("/api/v1/cmdb/resources").header("Authorization","Bearer "+token).param("q","x".repeat(121))).andExpect(status().isBadRequest());
    }
    @Test void editsPreserveAttributesRelationsAndResourceIdentity()throws Exception{
        var data=command(prefix);long id=create(data).path("id").asLong();jdbc.sql("UPDATE cmdb_resource SET attributes_json='{}' WHERE id=:id").param("id",id).update();
        jdbc.sql("INSERT INTO cmdb_relation(source_resource_id,target_resource_id,relation_type) VALUES (:id,1,'DEPENDS_ON')").param("id",id).update();data.put("version",0);data.put("description","维护说明");putStatus(id,data,200);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM cmdb_relation WHERE source_resource_id=:id").param("id",id).query(Long.class).single()).isEqualTo(1);assertThat(jdbc.sql("SELECT attributes_json FROM cmdb_resource WHERE id=:id").param("id",id).query(String.class).single()).isEqualTo("{}");
    }
    @Test void registeredResourceIsImmediatelyUsableByAlertIntake()throws Exception{
        long id=create(command(prefix)).path("id").asLong();var payload=Map.of("source","cmdb-maintenance-it","externalEventId",prefix,"resourceCode",prefix,"severity","P3","status","FIRING","title",prefix,"labels",Map.of());
        var r=json.readTree(mvc.perform(post("/api/v1/alerts/intake").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(jdbc.sql("SELECT service_resource_id FROM alert_event WHERE id=:id").param("id",r.path("data").path("alertId").asLong()).query(Long.class).single()).isEqualTo(id);
    }
}
