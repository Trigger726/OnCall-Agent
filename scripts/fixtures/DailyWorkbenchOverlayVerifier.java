import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;

class DailyWorkbenchOverlayVerifier {
    static boolean allowed(String name) {
        return name.startsWith("BOOT-INF/classes/static/")
            || name.matches("BOOT-INF/classes/org/trigger/opspilot/cmdb/Cmdb(?:Controller|Service)(?:\\$[^/]+)?\\.class");
    }
    static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    public static void main(String[] args) throws Exception {
        int protectedCount = 0, additions = 0;
        var changed = new ArrayList<String>();
        try (var before = new JarFile(args[0]); var after = new JarFile(args[1])) {
            for (var entries = before.entries(); entries.hasMoreElements();) {
                var entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                var fresh = after.getJarEntry(entry.getName());
                if (fresh == null || entry.getMethod() != fresh.getMethod()) throw new IllegalStateException("Removed/recompressed: " + entry.getName());
                if (!hash(before.getInputStream(entry).readAllBytes()).equals(hash(after.getInputStream(fresh).readAllBytes()))) {
                    if (!allowed(entry.getName())) throw new IllegalStateException("Unrelated change: " + entry.getName());
                    changed.add(entry.getName());
                }
                if (!allowed(entry.getName())) protectedCount++;
            }
            for (var entries = after.entries(); entries.hasMoreElements();) {
                var entry = entries.nextElement();
                if (!entry.isDirectory() && before.getEntry(entry.getName()) == null) {
                    if (!allowed(entry.getName())) throw new IllegalStateException("Unrelated addition: " + entry.getName());
                    additions++;
                }
            }
        }
        for (var suffix : List.of("/CmdbController.class", "/CmdbService.class", "/static/index.html")) {
            if (changed.stream().noneMatch(n -> n.endsWith(suffix))) throw new IllegalStateException("Required change missing: " + suffix);
        }
        System.out.println("{\"status\":\"PASS\",\"protectedEntries\":" + protectedCount + ",\"onlyCmdbAndStaticChanged\":true,\"addedEntries\":" + additions + ",\"changedEntries\":["
                + String.join(",", changed.stream().map(n -> "\"" + n + "\"").toList()) + "]}");
    }
}
