import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;

class AlertIntakeOverlayVerifier {
    static boolean allowed(String name) {
        return name.matches("BOOT-INF/classes/org/trigger/opspilot/(?:security/SecurityConfig|alert/AlertController)(?:\\$[^/]+)?\\.class");
    }
    static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    public static void main(String[] args) throws Exception {
        int protectedCount = 0;
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
                if (!entry.isDirectory() && before.getEntry(entry.getName()) == null && !allowed(entry.getName())) {
                    throw new IllegalStateException("Unrelated addition: " + entry.getName());
                }
            }
        }
        if (changed.stream().noneMatch(n -> n.endsWith("/AlertController.class")) || changed.stream().noneMatch(n -> n.endsWith("/SecurityConfig.class"))) {
            throw new IllegalStateException("Required intake classes did not change");
        }
        System.out.println("{\"protectedEntries\":" + protectedCount + ",\"onlyIntakeClassesChanged\":true,\"changedEntries\":["
                + String.join(",", changed.stream().map(n -> "\"" + n + "\"").toList()) + "]}");
    }
}
