import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/** Ages only the first accepted coverage in this runner's owned H2 database, never arbitrary SQL. */
public class SwapRevocationDeadlineFixture {
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("Require owned database and swap");
        Path root=Path.of(System.getenv("OPSPILOT_PAIR_FIXTURE_ROOT")).toRealPath(),database=Path.of(args[0]).toAbsolutePath().normalize();
        if(!root.getFileName().toString().startsWith("run-")||!root.getParent().getFileName().toString().equals("oncall-swap-revocation-ui-it")
                ||!database.getParent().toRealPath().equals(root.resolve("database").toRealPath())||!database.getFileName().toString().equals("opspilot"))throw new IllegalArgumentException("Require owned pair fixture");
        long swap=Long.parseLong(args[1]);if(swap<1)throw new IllegalArgumentException("Invalid swap identity");
        String url="jdbc:h2:file:"+database.toString().replace('\\','/')+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0;AUTO_SERVER=TRUE;AUTO_SERVER_PORT=9972";
        try(var connection=DriverManager.getConnection(url,"sa","")){
            if(!connection.getMetaData().getDatabaseProductName().equals("H2"))throw new IllegalStateException("Require actual H2");
            connection.setAutoCommit(false);long source,replacement;LocalDateTime end;
            try(var statement=connection.prepareStatement("SELECT s.first_shift_id,s.first_replacement_shift_id,CURRENT_TIMESTAMP(6) FROM oncall_shift_swap s WHERE s.id=? AND s.status='ACCEPTED' AND NOT EXISTS (SELECT 1 FROM oncall_swap_revocation r WHERE r.swap_id=s.id)")){
                statement.setLong(1,swap);try(var rows=statement.executeQuery()){if(!rows.next())throw new IllegalStateException("Require owned accepted pair without revocation");source=rows.getLong(1);replacement=rows.getLong(2);end=rows.getObject(3,LocalDateTime.class).minusSeconds(1);}
            }
            try(var statement=connection.prepareStatement("UPDATE oncall_shift SET starts_at=?,ends_at=? WHERE id IN (?,?) AND cancelled_at IS NULL")){
                statement.setTimestamp(1,Timestamp.valueOf(end.minusHours(1)));statement.setTimestamp(2,Timestamp.valueOf(end));statement.setLong(3,source);statement.setLong(4,replacement);if(statement.executeUpdate()!=2)throw new IllegalStateException("Missing uncancelled owned rows");}
            try(var statement=connection.prepareStatement("UPDATE oncall_shift_swap SET first_starts_at=?,first_ends_at=? WHERE id=? AND status='ACCEPTED'")){
                statement.setTimestamp(1,Timestamp.valueOf(end.minusHours(1)));statement.setTimestamp(2,Timestamp.valueOf(end));statement.setLong(3,swap);if(statement.executeUpdate()!=1)throw new IllegalStateException("Missing owned request");}
            connection.commit();
        }
        System.out.println("{\"changed\":3,\"swapId\":"+swap+",\"actualProduct\":\"H2\"}");
    }
}
