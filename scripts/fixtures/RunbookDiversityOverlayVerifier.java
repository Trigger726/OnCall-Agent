import java.util.*;
import java.util.jar.*;
import java.security.MessageDigest;

/** A retrieval-only increment: UI, assistant identity, libraries and migration bytes must survive. */
public class RunbookDiversityOverlayVerifier {
    static boolean allowed(String name) {
        return name.matches("BOOT-INF/classes/org/trigger/opspilot/runbook/(?:Bm25Retriever|RunbookSemanticIndexService|RunbookService)(?:\\$[^/]+)?\\.class");
    }
    static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    public static void main(String[] args) throws Exception {
        int preserved = 0, staticPreserved = 0;
        var changes = new ArrayList<String>();
        try (var before = new JarFile(args[0]); var after = new JarFile(args[1])) {
            var entries = before.entries();
            while (entries.hasMoreElements()) {
                var old = entries.nextElement(); if (old.isDirectory()) continue;
                var fresh = after.getJarEntry(old.getName());
                if (fresh == null) throw new IllegalStateException("Removed entry: " + old.getName());
                if (old.getMethod() != fresh.getMethod()) throw new IllegalStateException("Compression changed: " + old.getName());
                if (!hash(before.getInputStream(old).readAllBytes()).equals(hash(after.getInputStream(fresh).readAllBytes()))) {
                    if (!allowed(old.getName())) throw new IllegalStateException("Unrelated change: " + old.getName());
                    changes.add(old.getName());
                } else {
                    preserved++;
                    if (old.getName().startsWith("BOOT-INF/classes/static/")) staticPreserved++;
                }
            }
            var added = after.entries();
            while (added.hasMoreElements()) {
                var entry = added.nextElement(); if (entry.isDirectory() || before.getJarEntry(entry.getName()) != null) continue;
                if (!allowed(entry.getName())) throw new IllegalStateException("Unrelated addition: " + entry.getName());
                changes.add(entry.getName());
            }
        }
        if (changes.size() < 3 || staticPreserved == 0) throw new IllegalStateException("Missing retrieval/UI proof");
        System.out.println("{\"unchangedEntries\":" + preserved + ",\"staticEntriesPreserved\":" + staticPreserved
                + ",\"onlyRetrievalClassesChanged\":true,\"changedEntries\":["
                + String.join(",", changes.stream().map(s -> "\"" + s + "\"").toList()) + "]}");
    }
}
