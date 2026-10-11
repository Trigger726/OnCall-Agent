package org.trigger.opspilot.oncall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** SQL migration fixtures, not business publications or channel-delivery evidence. */
final class OpenNotificationRedriveMigrationScenarios {
    static void verify(String url,String user,String password,String product,String schema) throws Exception {
        Flyway.configure().dataSource(url,user,password).target("41").load().migrate();
        String columns,history;List<List<String>> before;
        try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement()) {
            assertThat(c.getMetaData().getDatabaseProductName()).isEqualTo(product);assertThat(c.getCatalog()).isEqualTo(schema);
            for(String state:List.of("PENDING","CLAIMED","DELIVERED","FAILED","SKIPPED")) {
                try(var insert=c.prepareStatement("""
                        INSERT INTO oncall_open_handoff(schedule_id,source_shift_id,source_version,requester_id,request_key,starts_at,ends_at,reason)
                        SELECT schedule_id,id,version,user_id,?,starts_at,ends_at,'migration fixture only' FROM oncall_shift ORDER BY id LIMIT 1
                        """)){insert.setString(1,UUID.randomUUID().toString());assertThat(insert.executeUpdate()).isEqualTo(1);}
                long id;try(var r=s.executeQuery("SELECT MAX(id) FROM oncall_open_handoff")){r.next();id=r.getLong(1);}
                s.executeUpdate("INSERT INTO oncall_open_handoff_publication(handoff_id,event_version,captured_at,snapshot_json) VALUES ("+id+",0,CURRENT_TIMESTAMP(6),'{\"note\":\"旧候选 🚦\"}')");
                try(var insert=c.prepareStatement("""
                        INSERT INTO oncall_open_handoff_notification(handoff_id,event_version,recipient_id,delivery_key,payload_json,status,version,attempts,
                          lease_token,lease_until,last_http_status,last_error_code,delivered_at)
                        VALUES (?,0,1,?,'{"eventVersion":0,"note":"旧载荷 🚦"}',?,9,2,?, ?, ?, ?, ?)
                        """)){insert.setLong(1,id);insert.setString(2,UUID.randomUUID().toString());insert.setString(3,state);insert.setString(4,state.equals("CLAIMED")?UUID.randomUUID().toString():null);
                    var now=java.sql.Timestamp.valueOf(java.time.LocalDateTime.of(2026,10,11,10,0,0,123456000));insert.setTimestamp(5,state.equals("CLAIMED")?now:null);
                    Integer http=state.equals("FAILED")?Integer.valueOf(400):state.equals("DELIVERED")?Integer.valueOf(200):null;
                    insert.setObject(6,http);insert.setString(7,state.equals("FAILED")?"HTTP_400":null);insert.setTimestamp(8,state.equals("DELIVERED")?now:null);assertThat(insert.executeUpdate()).isEqualTo(1);}
            }
            try(var r=s.executeQuery("SELECT * FROM oncall_open_handoff_notification WHERE 1=0")){var names=new ArrayList<String>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)names.add(r.getMetaData().getColumnName(i));columns=String.join(",",names);}
            before=rows(c,"SELECT "+columns+" FROM oncall_open_handoff_notification ORDER BY id");history=new ObjectMapper().writeValueAsString(rows(c,"SELECT version,checksum FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank"));
        }
        assertThat(Flyway.configure().dataSource(url,user,password).target("42").load().migrate().migrationsExecuted).isEqualTo(1);
        try(var c=DriverManager.getConnection(url,user,password)) {
            assertThat(rows(c,"SELECT "+columns+" FROM oncall_open_handoff_notification ORDER BY id")).isEqualTo(before);
            assertThat(new ObjectMapper().writeValueAsString(rows(c,"SELECT version,checksum FROM flyway_schema_history WHERE version IS NOT NULL AND version<>'42' ORDER BY installed_rank"))).isEqualTo(history);
            assertThat(rows(c,"SELECT cycle_start_attempts FROM oncall_open_handoff_notification ORDER BY id")).containsExactlyElementsOf(Collections.nCopies(5,List.of("0")));
            assertThat(rows(c,"SELECT COUNT(*) FROM oncall_open_notification_redrive")).isEqualTo(List.of(List.of("0")));
            assertThat(rows(c,"SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE AND version IS NOT NULL")).isEqualTo(List.of(List.of("42")));
            System.out.println("OPEN_NOTIFICATION_REDRIVE_MIGRATION "+new ObjectMapper().writeValueAsString(Map.of("product",product,"schema",schema,"version",c.getMetaData().getDatabaseProductVersion(),"legacyStates",5,"legacyColumnsAnd41ChecksumsPreserved",true,"defaultCycleStart",0,"redriveRows",0,"migrations",42)));
        }
        assertThat(Flyway.configure().dataSource(url,user,password).target("42").load().migrate().migrationsExecuted).isZero();
    }
    private static List<List<String>> rows(Connection c,String sql) throws Exception {var out=new ArrayList<List<String>>();try(var s=c.createStatement();var r=s.executeQuery(sql)){while(r.next()){var row=new ArrayList<String>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.add(r.getString(i));out.add(row);}}return out;}
}
