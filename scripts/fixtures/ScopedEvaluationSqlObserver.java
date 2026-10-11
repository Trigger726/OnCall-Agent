import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.Base64;

/** Post-shutdown, acceptance-only SELECTs against an existing isolated H2 file. */
public class ScopedEvaluationSqlObserver {
    public static void main(String[] args) throws Exception {
        if(args.length!=1 || args[0].contains(";")) throw new IllegalArgumentException("One H2 base path required");
        try(var c=DriverManager.getConnection("jdbc:h2:file:"+args[0].replace('\\','/')+";IFEXISTS=TRUE;ACCESS_MODE_DATA=r;MODE=MySQL;DATABASE_TO_LOWER=TRUE","sa","")) {
            boolean scope;try(var fields=c.getMetaData().getColumns(null,null,"runbook_retrieval_query","scope_type")){scope=fields.next();}
            try(var s=c.createStatement()) {
                print(s,"HISTORY","SELECT version,checksum FROM flyway_schema_history WHERE success=TRUE ORDER BY installed_rank");
                print(s,"QUERY","SELECT id,query_text,query_hash,source_type,results_json,created_by"+(scope?",scope_type,scope_json":"")+" FROM runbook_retrieval_query ORDER BY id");
                print(s,"CASE","SELECT id,case_key,query_text,expected_stable_key,enabled,judgment_id"+(scope?",scope_type,scope_json":"")+" FROM runbook_retrieval_eval_case ORDER BY id");
                print(s,"RUN","SELECT id,case_count,judgment_count,dataset_version FROM runbook_retrieval_eval_run ORDER BY id");
            }
        }
    }
    private static void print(java.sql.Statement statement,String tag,String sql) throws Exception {
        try(var rows=statement.executeQuery(sql)){while(rows.next()){var line=new StringBuilder(tag);for(int i=1;i<=rows.getMetaData().getColumnCount();i++){String value=rows.getString(i);line.append('\t').append(value==null?"-":Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));}System.out.println(line);}}
    }
}
