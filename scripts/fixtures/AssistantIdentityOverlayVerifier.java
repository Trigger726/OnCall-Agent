import java.util.*;
import java.util.jar.*;
import java.security.MessageDigest;

/** Identity fix only: no unrelated backend, dependency or migration may enter the preview. */
public class AssistantIdentityOverlayVerifier {
    static boolean allowed(String name) {
        return name.startsWith("BOOT-INF/classes/static/") || name.matches(
            "BOOT-INF/classes/org/trigger/opspilot/assistant/Assistant(?:Service|Controller|Identity)(?:\\$[^/]+)?\\.class");
    }
    static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    public static void main(String[] args) throws Exception {
        int preserved = 0, changed = 0, added = 0;
        var backend = new ArrayList<String>();
        try (var before = new JarFile(args[0]); var after = new JarFile(args[1])) {
            var entries = before.entries();
            while (entries.hasMoreElements()) {
                var old = entries.nextElement(); if (old.isDirectory()) continue;
                var fresh = after.getJarEntry(old.getName());
                if (fresh == null) throw new IllegalStateException("Removed entry: " + old.getName());
                boolean same = hash(before.getInputStream(old).readAllBytes()).equals(hash(after.getInputStream(fresh).readAllBytes()));
                if (old.getMethod() != fresh.getMethod()) throw new IllegalStateException("Compression changed: " + old.getName());
                if (!same) {
                    if (!allowed(old.getName())) throw new IllegalStateException("Unrelated change: " + old.getName());
                    changed++;
                    if (!old.getName().startsWith("BOOT-INF/classes/static/")) backend.add(old.getName());
                } else if (!old.getName().startsWith("BOOT-INF/classes/static/")) preserved++;
            }
            var freshEntries = after.entries();
            while (freshEntries.hasMoreElements()) {
                var entry = freshEntries.nextElement(); if (entry.isDirectory() || before.getJarEntry(entry.getName()) != null) continue;
                if (!allowed(entry.getName())) throw new IllegalStateException("Unrelated addition: " + entry.getName());
                added++;
                if (!entry.getName().startsWith("BOOT-INF/classes/static/")) backend.add(entry.getName());
            }
        }
        if (changed < 2 || added < 2) throw new IllegalStateException("Missing identity/UI fix");
        System.out.println("{\"nonStaticEntriesPreserved\":" + preserved + ",\"changedEntries\":" + changed
                + ",\"addedEntries\":" + added + ",\"onlyIdentityAndStaticChanged\":true,\"backendEntries\":["
                + String.join(",", backend.stream().map(s -> "\"" + s + "\"").toList()) + "]}");
    }
}
