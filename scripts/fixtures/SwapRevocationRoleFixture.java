import java.nio.file.Path;
import java.sql.DriverManager;

/** Changes only seeded manager #3 in this runner's owned H2 fixture to test stale UI qualification. */
public class SwapRevocationRoleFixture {
    public static void main(String[] args) throws Exception {
        if(args.length!=2||!args[1].equals("DEMOTE")&&!args[1].equals("RESTORE"))throw new IllegalArgumentException("Require owned database and exact role mode");
        Path root=Path.of(System.getenv("OPSPILOT_PAIR_FIXTURE_ROOT")).toRealPath(),database=Path.of(args[0]).toAbsolutePath().normalize();
        if(!root.getFileName().toString().startsWith("run-")||!root.getParent().getFileName().toString().equals("oncall-swap-revocation-ui-it")
                ||!database.getParent().toRealPath().equals(root.resolve("database").toRealPath())||!database.getFileName().toString().equals("opspilot"))throw new IllegalArgumentException("Require owned role fixture");
        boolean demote=args[1].equals("DEMOTE");
        String url="jdbc:h2:file:"+database.toString().replace('\\','/')+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0;AUTO_SERVER=TRUE;AUTO_SERVER_PORT=9972";
        try(var connection=DriverManager.getConnection(url,"sa","")){
            if(!connection.getMetaData().getDatabaseProductName().equals("H2"))throw new IllegalStateException("Require actual H2");
            try(var statement=connection.prepareStatement("UPDATE sys_user SET role_code=? WHERE id=3 AND username='lina' AND role_code=?")){
                statement.setString(1,demote?"ON_CALL":"OPS_MANAGER");statement.setString(2,demote?"OPS_MANAGER":"ON_CALL");if(statement.executeUpdate()!=1)throw new IllegalStateException("Require owned seeded role transition");}
        }
        System.out.println("{\"changed\":1,\"actorId\":3,\"mode\":\""+args[1]+"\",\"actualProduct\":\"H2\"}");
    }
}
