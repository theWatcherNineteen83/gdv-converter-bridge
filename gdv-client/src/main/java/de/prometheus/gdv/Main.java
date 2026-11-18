package de.prometheus.gdv;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Main {

    public static void main(String[] args) throws Exception {
        Map<String, String> a = parse(args);
        Path gdvHome = Path.of(required(a, "gdv-home", "GDV_HOME"));
        Path in = Path.of(required(a, "in", null));
        Path out = Path.of(required(a, "out", null));
        String release = required(a, "release", null);
        String workerCp = a.get("worker-cp");
        boolean nativeMode = a.containsKey("native");

        try (GdvBridge bridge = nativeMode
                ? GdvBridge.nativeMode(gdvHome)
                : new GdvBridge(gdvHome, Path.of(required(a, "java8", "GDV_JAVA8")), null,
                        workerCp == null ? List.of() : List.of(Path.of(workerCp)))) {
            bridge.convertFile(in, out, release);
        }
        System.out.println("OK " + out + " (" + Files.size(out) + " Bytes, "
                + (nativeMode ? "native" : workerCp != null ? "worker" : "cli") + ")");
    }

    private static String required(Map<String, String> a, String key, String env) {
        String v = a.get(key);
        if (v == null && env != null) v = System.getenv(env);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Parameter --" + key + (env != null ? " oder $" + env : "") + " fehlt");
        }
        return v;
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].startsWith("--")) m.put(args[i].substring(2), args[i + 1]);
        }
        return m;
    }
}
