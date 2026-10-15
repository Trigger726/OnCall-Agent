import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;

public class AnswerReaderOverlayVerifier {
    static boolean processor(String name) {
        return name.matches("BOOT-INF/classes/org/trigger/opspilot/runbook/RunbookTextProcessor(?:\\$[^/]+)?\\.class");
    }
    static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    public static void main(String[] args) throws Exception {
        int preserved=0;var changed=new ArrayList<String>();
        try(var before=new JarFile(args[0]);var after=new JarFile(args[1])){
            var entries=before.entries();while(entries.hasMoreElements()){
                var old=entries.nextElement();if(old.isDirectory())continue;var fresh=after.getJarEntry(old.getName());
                if(fresh==null||old.getMethod()!=fresh.getMethod())throw new IllegalStateException("Removed/recompressed: "+old.getName());
                boolean same=hash(before.getInputStream(old).readAllBytes()).equals(hash(after.getInputStream(fresh).readAllBytes()));
                if(!same){if(!processor(old.getName())&&!old.getName().startsWith("BOOT-INF/classes/static/"))throw new IllegalStateException("Unrelated: "+old.getName());changed.add(old.getName());}
                if(!processor(old.getName())&&!old.getName().startsWith("BOOT-INF/classes/static/"))preserved++;
            }
            var added=after.entries();while(added.hasMoreElements()){var entry=added.nextElement();if(entry.isDirectory()||before.getEntry(entry.getName())!=null)continue;
                if(!processor(entry.getName())&&!entry.getName().startsWith("BOOT-INF/classes/static/"))throw new IllegalStateException("Unrelated addition: "+entry.getName());changed.add(entry.getName());}
        }
        if(changed.stream().noneMatch(name->name.endsWith("RunbookTextProcessor.class")))throw new IllegalStateException("Missing chunk fix");
        System.out.println("{\"protectedNonStaticEntriesPreserved\":"+preserved+",\"onlyProcessorAndStaticChanged\":true,\"changedEntries\":["+String.join(",",changed.stream().map(name->"\""+name+"\"").toList())+"]}");
    }
}
