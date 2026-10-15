import java.util.*;
import java.util.jar.*;
import java.security.MessageDigest;

/** The citation reader changes only two Runbook class families and static UI resources. */
public class CitationSourceOverlayVerifier {
    static boolean allowed(String name) {
        return name.startsWith("BOOT-INF/classes/static/") || name.matches(
                "BOOT-INF/classes/org/trigger/opspilot/runbook/(?:RunbookController|RunbookService)(?:\\$[^/]+)?\\.class");
    }
    static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    public static void main(String[] args) throws Exception {
        int protectedEntries=0;var changed=new ArrayList<String>();
        try(var before=new JarFile(args[0]);var after=new JarFile(args[1])) {
            var oldEntries=before.entries();
            while(oldEntries.hasMoreElements()) {
                var old=oldEntries.nextElement();if(old.isDirectory())continue;
                var fresh=after.getJarEntry(old.getName());
                if(fresh==null||old.getMethod()!=fresh.getMethod())throw new IllegalStateException("Removed or recompressed: "+old.getName());
                boolean same=hash(before.getInputStream(old).readAllBytes()).equals(hash(after.getInputStream(fresh).readAllBytes()));
                if(!same){if(!allowed(old.getName()))throw new IllegalStateException("Unrelated change: "+old.getName());changed.add(old.getName());}
                if(!allowed(old.getName()))protectedEntries++;
            }
            var newEntries=after.entries();while(newEntries.hasMoreElements()) {
                var fresh=newEntries.nextElement();if(fresh.isDirectory()||before.getEntry(fresh.getName())!=null)continue;
                if(!allowed(fresh.getName()))throw new IllegalStateException("Unrelated addition: "+fresh.getName());changed.add(fresh.getName());
            }
        }
        if(changed.stream().noneMatch(name->name.endsWith("RunbookController.class"))
                ||changed.stream().noneMatch(name->name.endsWith("RunbookService$CitationView.class"))
                ||changed.stream().noneMatch(name->name.equals("BOOT-INF/classes/static/index.html")))throw new IllegalStateException("Missing citation increment");
        System.out.println("{\"protectedEntriesUnchanged\":"+protectedEntries+",\"onlyCitationClassesAndStaticChanged\":true,\"changedEntries\":["
                +String.join(",",changed.stream().map(name->"\""+name+"\"").toList())+"]}");
    }
}
