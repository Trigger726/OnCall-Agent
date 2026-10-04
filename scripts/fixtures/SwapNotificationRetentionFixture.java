import java.nio.file.Path;
import java.sql.DriverManager;

/** Only ages one notification in this runner's owned H2 fixture; no arbitrary SQL or remote database. */
public class SwapNotificationRetentionFixture {
    public static void main(String[] args) throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("Require owned database, notification and swap");
        Path root=Path.of(System.getenv("OPSPILOT_RETENTION_FIXTURE_ROOT")).toRealPath();
        Path database=Path.of(args[0]).toAbsolutePath().normalize();
        if(!root.getFileName().toString().startsWith("run-")||!root.getParent().getFileName().toString().equals("oncall-swap-notification-it")
                ||!database.getParent().toRealPath().equals(root.resolve("database").toRealPath())||!database.getFileName().toString().equals("opspilot")){
            throw new IllegalArgumentException("Only the owned retention fixture is allowed");
        }
        long id=Long.parseLong(args[1]),swap=Long.parseLong(args[2]);if(id<1||swap<1)throw new IllegalArgumentException("Invalid owned row identity");
        String url="jdbc:h2:file:"+database.toString().replace('\\','/')+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0;AUTO_SERVER=TRUE;AUTO_SERVER_PORT=9977";
        try(var connection=DriverManager.getConnection(url,"sa","")){
            if(!connection.getMetaData().getDatabaseProductName().equals("H2"))throw new IllegalStateException("Require actual H2 fixture");
            try(var statement=connection.prepareStatement("UPDATE oncall_swap_notification SET payload_expires_at=CURRENT_TIMESTAMP(6) WHERE id=? AND swap_id=? AND payload_erased_at IS NULL")){
                statement.setLong(1,id);statement.setLong(2,swap);if(statement.executeUpdate()!=1)throw new IllegalStateException("Missing owned live payload");
            }
        }
        System.out.println("{\"changed\":1,\"notificationId\":"+id+",\"swapId\":"+swap+"}");
    }
}
