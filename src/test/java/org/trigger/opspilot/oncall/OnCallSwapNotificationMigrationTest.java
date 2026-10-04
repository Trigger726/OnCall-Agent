package org.trigger.opspilot.oncall;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class OnCallSwapNotificationMigrationTest {
    @Test void shouldFreezeLegacyDeadlineWithoutErasingV35PayloadOrTechnicalHistory() throws Exception {
        String url="jdbc:h2:mem:notification-upgrade-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").target("35").load().migrate();
        try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.createStatement()){
            assertThat(statement.executeUpdate("""
                    INSERT INTO oncall_shift_swap(requester_id,target_user_id,request_key,first_schedule_id,first_shift_id,first_version,
                      first_starts_at,first_ends_at,second_schedule_id,second_shift_id,second_version,second_starts_at,second_ends_at,reason)
                    SELECT 2,3,'migration-fixture',a.schedule_id,a.id,a.version,a.starts_at,a.ends_at,b.schedule_id,b.id,b.version,b.starts_at,b.ends_at,'migration only'
                    FROM oncall_shift a JOIN oncall_shift b ON a.id<b.id ORDER BY a.id,b.id LIMIT 1
                    """)).isEqualTo(1);
            for(int recipient:List.of(2,3))assertThat(statement.executeUpdate("""
                    INSERT INTO oncall_swap_notification(swap_id,event_version,event_status,recipient_id,recipient_name,delivery_key,payload_json,status,
                      total_attempts,last_http_status,last_retry_from_version,last_retry_reason,created_at)
                    SELECT id,0,'PENDING',%d,'legacy','legacy-key-%d','{"fixture":"frozen"}','FAILED',3,401,1,'legacy retry',
                      TIMESTAMPADD(DAY,-60,CURRENT_TIMESTAMP(6)) FROM oncall_shift_swap
                    """.formatted(recipient,recipient))).isEqualTo(1);
        }
        var before=history(url);
        assertThat(Flyway.configure().dataSource(url,"sa","").target("36").load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(history(url)).isEqualTo(before);
        try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.createStatement();
            var rows=statement.executeQuery("SELECT created_at,payload_expires_at,payload_erased_at FROM oncall_swap_notification ORDER BY id")){
            int count=0;while(rows.next()){count++;assertThat(rows.getObject(2,LocalDateTime.class)).isEqualTo(rows.getObject(1,LocalDateTime.class).plusDays(30));assertThat(rows.getObject(3)).isNull();}
            assertThat(count).isEqualTo(2);
        }
        assertThat(Flyway.configure().dataSource(url,"sa","").target("36").load().migrate().migrationsExecuted).isZero();
        assertThat(history(url)).isEqualTo(before);
    }
    private List<List<Object>> history(String url)throws Exception{
        var result=new ArrayList<List<Object>>();
        try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.createStatement();var rows=statement.executeQuery("""
                SELECT id,swap_id,event_version,event_status,recipient_id,recipient_name,delivery_key,payload_json,status,version,attempts,total_attempts,
                  next_attempt_at,lease_token,lease_until,last_http_status,last_error_code,delivered_at,last_retry_from_version,last_retry_reason,created_at
                FROM oncall_swap_notification ORDER BY id
                """)){while(rows.next()){var row=new ArrayList<Object>();for(int i=1;i<=21;i++)row.add(rows.getObject(i));result.add(row);}}
        return result;
    }
}
