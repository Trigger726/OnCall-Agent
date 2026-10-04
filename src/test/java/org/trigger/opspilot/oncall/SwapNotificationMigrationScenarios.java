package org.trigger.opspilot.oncall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** The exact same populated V35 upgrade assertions run on H2 and an owned real MySQL database. */
final class SwapNotificationMigrationScenarios {
    static final String CASE="shouldFreezeLegacyDeadlineWithoutErasingV35PayloadOrTechnicalHistory";
    static final List<String> STATES=List.of("PENDING","CLAIMED","DELIVERED","FAILED","SKIPPED");
    private static final String LEGACY_COLUMNS="id,swap_id,event_version,event_status,recipient_id,recipient_name,delivery_key,payload_json,status,version,attempts,total_attempts,next_attempt_at,lease_token,lease_until,last_http_status,last_error_code,delivered_at,last_retry_from_version,last_retry_reason,created_at";
    private static final ObjectMapper JSON=new ObjectMapper();

    static Map<String,Object> verify(String url,String user,String password) throws Exception {
        Flyway.configure().dataSource(url,user,password).target("35").load().migrate();
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()){
            assertThat(count(connection,"flyway_schema_history WHERE version='35' AND success=TRUE")).isEqualTo(1);
            assertThat(count(connection,"flyway_schema_history WHERE version='36' AND success=TRUE")).isZero();
            assertThat(columns(connection)).containsExactly(LEGACY_COLUMNS.split(","));
            assertThat(statement.executeUpdate("""
                    INSERT INTO oncall_shift_swap(requester_id,target_user_id,request_key,first_schedule_id,first_shift_id,first_version,
                      first_starts_at,first_ends_at,second_schedule_id,second_shift_id,second_version,second_starts_at,second_ends_at,reason)
                    SELECT 2,3,'migration-fixture',a.schedule_id,a.id,a.version,a.starts_at,a.ends_at,b.schedule_id,b.id,b.version,b.starts_at,b.ends_at,'migration only'
                    FROM oncall_shift a JOIN oncall_shift b ON a.id<b.id ORDER BY a.id,b.id LIMIT 1
                    """)).isEqualTo(1);
            LocalDateTime now;
            try(var rows=statement.executeQuery("SELECT CURRENT_TIMESTAMP(6)")){rows.next();now=rows.getObject(1,LocalDateTime.class);}
            try(var insert=connection.prepareStatement("""
                    INSERT INTO oncall_swap_notification(swap_id,event_version,event_status,recipient_id,recipient_name,delivery_key,payload_json,status,
                      version,attempts,total_attempts,next_attempt_at,lease_token,lease_until,last_http_status,last_error_code,delivered_at,
                      last_retry_from_version,last_retry_reason,created_at)
                    SELECT id,?,'PENDING',?,'旧值班员 🚦',?,?,?,7,2,5,?,?,?,?,?, ?,1,?,?
                    FROM oncall_shift_swap WHERE request_key='migration-fixture'
                    """)){
                for(int i=0;i<10;i++){
                    String state=STATES.get(i%5);LocalDateTime created=(i<5?now.minusDays(60):now.plusDays(7)).withNano((123450+i)*1000);
                    insert.setInt(1,i);insert.setInt(2,i%2==0?2:3);insert.setString(3,UUID.randomUUID().toString());
                    insert.setString(4,JSON.writeValueAsString(Map.of("fixture","中文 🚦 frozen \\\\ line\n quote \"","row",i)));insert.setString(5,state);
                    insert.setObject(6,created.plusDays(1));insert.setString(7,state.equals("CLAIMED")?UUID.randomUUID().toString():null);
                    insert.setObject(8,state.equals("CLAIMED")?now.plusDays(1).withNano(654321000):null);
                    insert.setObject(9,state.equals("DELIVERED")?204:401);insert.setString(10,state.equals("DELIVERED")?null:"LEGACY_"+state);
                    insert.setObject(11,state.equals("DELIVERED")?created.plusHours(1):null);insert.setString(12,"原人工重试说明 "+i);insert.setObject(13,created);
                    assertThat(insert.executeUpdate()).isEqualTo(1);
                }
            }
            identity(connection,"V35_BEFORE",10);
        }
        var before=history(url,user,password);assertThat(before).hasSize(10);
        Map<String,Long> parentsBefore;var stateCounts=new TreeMap<String,Long>();
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()){
            parentsBefore=parents(connection);
            assertThat(count(connection,"oncall_swap_notification WHERE created_at<CURRENT_TIMESTAMP(6)")).isEqualTo(5);
            assertThat(count(connection,"oncall_swap_notification WHERE created_at>CURRENT_TIMESTAMP(6)")).isEqualTo(5);
            try(var rows=statement.executeQuery("SELECT status,COUNT(*) FROM oncall_swap_notification GROUP BY status")){while(rows.next())stateCounts.put(rows.getString(1),rows.getLong(2));}
            assertThat(stateCounts).hasSize(5);for(String state:STATES)assertThat(stateCounts.get(state)).isEqualTo(2);
        }
        int migrations=Flyway.configure().dataSource(url,user,password).target("36").load().migrate().migrationsExecuted;
        assertThat(migrations).isEqualTo(1);var after=history(url,user,password);assertThat(after).isEqualTo(before);
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()){
            assertThat(columns(connection)).containsExactly((LEGACY_COLUMNS+",payload_expires_at,payload_erased_at").split(","));
            assertThat(count(connection,"flyway_schema_history WHERE version='36' AND success=TRUE")).isEqualTo(1);
            assertThat(parents(connection)).isEqualTo(parentsBefore);
            try(var rows=statement.executeQuery("SELECT created_at,payload_expires_at,payload_erased_at FROM oncall_swap_notification ORDER BY id")){
                int count=0;while(rows.next()){count++;var created=rows.getObject(1,LocalDateTime.class);
                    assertThat(created.getNano()).isEqualTo((123450+count-1)*1000);
                    assertThat(rows.getObject(2,LocalDateTime.class)).isEqualTo(created.plusDays(30));assertThat(rows.getObject(3)).isNull();}
                assertThat(count).isEqualTo(10);
            }
            var index=new TreeMap<Short,String>();
            try(var rows=connection.getMetaData().getIndexInfo(connection.getCatalog(),null,"oncall_swap_notification",false,false)){
                while(rows.next())if("idx_swap_notification_payload_retention".equalsIgnoreCase(rows.getString("INDEX_NAME")))index.put(rows.getShort("ORDINAL_POSITION"),rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
            assertThat(index.values()).containsExactly("payload_erased_at","payload_expires_at","id");
            identity(connection,"V36_AFTER",10);
        }
        int repeat=Flyway.configure().dataSource(url,user,password).target("36").load().migrate().migrationsExecuted;
        assertThat(repeat).isZero();assertThat(history(url,user,password)).isEqualTo(before);
        try(var connection=DriverManager.getConnection(url,user,password)){assertThat(parents(connection)).isEqualTo(parentsBefore);}
        var result=new LinkedHashMap<String,Object>();result.put("case",CASE);result.put("rows",10);result.put("states",STATES);
        result.put("legacyColumns",21);result.put("legacyHistoryBeforeSha256",digest(before));result.put("legacyHistoryAfterSha256",digest(after));
        result.put("upgradeMigrations",migrations);result.put("repeatMigrations",repeat);result.put("allDeadlinesEqualCreatedPlus30Days",true);
        result.put("microsecondsPreserved",true);result.put("allPayloadsNotErased",true);result.put("retentionIndexColumns",List.of("payload_erased_at","payload_expires_at","id"));
        result.put("parentCountsUnchanged",true);result.put("parentCounts",parentsBefore);result.put("pastAndFutureRows",true);
        result.put("stateCounts",stateCounts);result.put("pastRows",5);result.put("futureRows",5);
        System.out.println("CP91_SWAP_NOTIFICATION_MIGRATION_RESULT "+JSON.writeValueAsString(result));return result;
    }
    private static void identity(Connection connection,String phase,int rows) throws Exception {
        var metadata=connection.getMetaData();String product=metadata.getDatabaseProductName();assertThat(product).isIn("H2","MySQL");
        if(product.equals("MySQL")){assertThat(metadata.getDatabaseProductVersion()).startsWith("8.4.");assertThat(connection.getCatalog()).isEqualTo("opspilot_swap_notification_migration_test");}
        assertThat(count(connection,"oncall_swap_notification")).isEqualTo(rows);
        System.out.println("CP91_SWAP_NOTIFICATION_MIGRATION_DATABASE "+JSON.writeValueAsString(Map.of("case",CASE,"phase",phase,"product",product,"version",metadata.getDatabaseProductVersion(),"schema",connection.getCatalog(),"rows",rows)));
    }
    private static List<String> columns(Connection connection)throws Exception {
        var result=new ArrayList<String>();try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT * FROM oncall_swap_notification WHERE 1=0")){
            for(int i=1;i<=rows.getMetaData().getColumnCount();i++)result.add(rows.getMetaData().getColumnName(i).toLowerCase(Locale.ROOT));}return result;
    }
    private static long count(Connection connection,String table)throws Exception {
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT COUNT(*) FROM "+table)){rows.next();return rows.getLong(1);}
    }
    private static Map<String,Long> parents(Connection connection)throws Exception {
        var result=new LinkedHashMap<String,Long>();for(String table:List.of("oncall_shift_swap","oncall_shift","sys_user","audit_log"))result.put(table,count(connection,table));return result;
    }
    private static List<List<Object>> history(String url,String user,String password)throws Exception {
        var result=new ArrayList<List<Object>>();try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement();var rows=statement.executeQuery("SELECT "+LEGACY_COLUMNS+" FROM oncall_swap_notification ORDER BY id")){
            while(rows.next()){var row=new ArrayList<Object>();for(int i=1;i<=21;i++)row.add(rows.getObject(i));result.add(row);}}return result;
    }
    private static String digest(Object value)throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)));}
}
