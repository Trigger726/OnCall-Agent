import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;

// Verify binary scope, including STORED nested JARs required by Spring Boot.
public class StaticUiOverlayVerifier {
    static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    public static void main(String[] args) throws Exception {
        int preserved=0,staticChanged=0;
        try(var before=new JarFile(args[0]);var after=new JarFile(args[1])) {
            var entries=before.entries();
            while(entries.hasMoreElements()) {
                var old=entries.nextElement();if(old.isDirectory())continue;
                var fresh=after.getJarEntry(old.getName());
                if(fresh==null)throw new IllegalStateException("Removed entry: "+old.getName());
                boolean same=hash(before.getInputStream(old).readAllBytes()).equals(hash(after.getInputStream(fresh).readAllBytes()));
                if(!old.getName().startsWith("BOOT-INF/classes/static/")) {
                    if(!same||old.getMethod()!=fresh.getMethod())throw new IllegalStateException("Changed business/loader/library: "+old.getName());
                    preserved++;
                } else if(!same)staticChanged++;
            }
            var added=after.entries();while(added.hasMoreElements()) {
                var entry=added.nextElement();
                if(before.getEntry(entry.getName())==null&&!entry.getName().startsWith("BOOT-INF/classes/static/"))
                    throw new IllegalStateException("Unexpected new entry: "+entry.getName());
            }
        }
        if(staticChanged==0)throw new IllegalStateException("No UI change in overlay");
        System.out.println("{\"businessEntriesPreserved\":"+preserved+",\"existingStaticEntriesChanged\":"+staticChanged+",\"onlyStaticChanged\":true}");
    }
}
