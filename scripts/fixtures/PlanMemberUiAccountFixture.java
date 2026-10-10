import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Offline new-account fixture; no published migration or existing member is changed. */
public class PlanMemberUiAccountFixture {
    public static void main(String[] args) throws Exception {
        if(args.length!=2||!java.util.Set.of("SEED","SNAPSHOT").contains(args[1]))throw new IllegalArgumentException("Exact owned fixture mode required");
        Path root=Path.of(System.getenv("OPSPILOT_MEMBER_UI_FIXTURE_ROOT")).toRealPath(),database=Path.of(args[0]).toAbsolutePath().normalize();
        if(!root.getFileName().toString().startsWith("run-")||!root.getParent().getFileName().toString().equals("oncall-plan-membership-ui-it")
            ||!database.getParent().toRealPath().equals(root)||!database.getFileName().toString().equals("database")
            ||!Files.isRegularFile(Path.of(database+".mv.db"))||!Path.of(database+".mv.db").toRealPath().getParent().equals(root))throw new IllegalArgumentException("Only existing runner-owned database allowed");
        try(var c=DriverManager.getConnection("jdbc:h2:file:"+database.toString().replace('\\','/')+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0;IFEXISTS=TRUE","sa","")){
            if(!c.getMetaData().getDatabaseProductName().equals("H2")||count(c,"SELECT COUNT(*) FROM flyway_schema_history WHERE version='39' AND success=TRUE")!=1)throw new IllegalStateException("Require actual migrated H2/V39");
            String before=fingerprint(c);int changed=0;
            if(args[1].equals("SEED")){
                if(count(c,"SELECT COUNT(*) FROM sys_user WHERE id=90 OR username='cp114-new'")!=0)throw new IllegalStateException("Fixture account already exists");
                try(var s=c.createStatement()){changed=s.executeUpdate("INSERT INTO sys_user(id,username,password_hash,display_name,role_code,department,status) SELECT 90,'cp114-new',password_hash,'新响应成员','ON_CALL','测试自有库','ACTIVE' FROM sys_user WHERE id=2 AND username='zhangwei'");}
                if(changed!=1||count(c,"SELECT COUNT(*) FROM oncall_schedule_member WHERE user_id=90")!=0)throw new IllegalStateException("New account must not gain implicit membership");
            }
            String after=fingerprint(c);if(!before.equals(after))throw new IllegalStateException("Fixture cannot change any on-call business or audit row");
            long members=count(c,"SELECT COUNT(*) FROM oncall_schedule_member WHERE user_id=90"),operations=count(c,"SELECT COUNT(*) FROM oncall_schedule_member_operation WHERE user_id=90");
            long explicit=count(c,"SELECT COUNT(*) FROM oncall_schedule_member WHERE schedule_id=1 AND user_id=90 AND version=0 AND origin='EXPLICIT' AND active=TRUE AND can_respond=TRUE AND can_manage=FALSE");
            long receipt=count(c,"SELECT COUNT(*) FROM oncall_schedule_member_operation WHERE schedule_id=1 AND user_id=90 AND actor_id=1 AND expected_version IS NULL AND result_version=0 AND active=TRUE AND can_respond=TRUE AND can_manage=FALSE");
            System.out.println("{\"actualProduct\":\"H2\",\"migration39\":true,\"mode\":\""+args[1]+"\",\"newUserId\":90,\"changedUsers\":"+changed+",\"memberRows\":"+members+",\"memberOperations\":"+operations+",\"explicitResponseOnlyVersion0\":"+explicit+",\"explicitNullActor1Receipt\":"+receipt+",\"businessBefore\":\""+before+"\",\"businessAfter\":\""+after+"\"}");
        }
    }
    private static long count(Connection c,String sql)throws Exception{try(var s=c.createStatement();var r=s.executeQuery(sql)){if(!r.next())throw new IllegalStateException("Missing count");return r.getLong(1);}}
    private static String fingerprint(Connection c)throws Exception{
        var digest=MessageDigest.getInstance("SHA-256");
        for(String table:java.util.List.of("oncall_schedule_member","oncall_schedule_member_operation","oncall_open_handoff","oncall_open_handoff_operation","oncall_shift","oncall_handoff","oncall_shift_swap","audit_log")){
            try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM "+table+" ORDER BY 1,2")){
                digest.update(table.getBytes(StandardCharsets.UTF_8));while(r.next())for(int i=1;i<=r.getMetaData().getColumnCount();i++){String v=r.getString(i);digest.update((v==null?"NULL":v.length()+":"+v).getBytes(StandardCharsets.UTF_8));digest.update((byte)0);}
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
