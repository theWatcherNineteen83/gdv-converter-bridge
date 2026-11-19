package de.prometheus.gdv;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CLI-Starter.
 *
 * Parameter:
 *   --gdv-home    GDVTools-Verzeichnis (oder $GDV_HOME)
 *   --in/--out    Ein-/Ausgabedatei (Richtung ueber die Endung .xml/.snn)
 *   --release     GDV-Release, z. B. 2003
 *   --native      nativer Java-21-Modus (kein Java 8 noetig)
 *   --java8       Java-8-Binary oder JRE-Verzeichnis; optional - sonst Aufloesung ueber
 *                 -Dgdv.java8 / $GDV_JAVA8 / jre8 neben GDVTools bzw. im Arbeitsverzeichnis
 *                 (unter Windows automatisch java.exe)
 *   --worker-cp   Klassenpfad des Workers, mehrere Eintraege mit dem plattformspezifischen
 *                 Trennzeichen (Linux ':', Windows ';'); die Worker-Hauptklasse wird darin
 *                 dynamisch gesucht (WorkerMainFinder)
 *   --worker-main vollqualifizierter Name der Worker-Hauptklasse (optional; sonst Suche
 *                 bzw. -Dgdv.worker.main)
 */
public final class Main {

    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--gui")) {
            TestGui.main(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        try {
            run(args);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "GDV-Konvertierung fehlgeschlagen", e);
            System.err.println("FEHLER: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        Map<String, String> a = parse(args);
        Path gdvHome = Path.of(required(a, "gdv-home", "GDV_HOME"));
        Path in = Path.of(required(a, "in", null));
        Path out = Path.of(required(a, "out", null));
        String release = required(a, "release", null);
        boolean nativeMode = a.containsKey("native");
        List<Path> workerCp = splitClasspath(a.get("worker-cp"));
        String workerMain = a.get("worker-main");
        String java8Arg = a.get("java8");
        if (java8Arg == null) java8Arg = System.getenv("GDV_JAVA8");

        String mode;
        try (GdvBridge bridge = nativeMode
                ? GdvBridge.nativeMode(gdvHome)
                : new GdvBridge(gdvHome, java8Arg == null ? null : Path.of(java8Arg), null,
                        workerCp, workerMain)) {
            bridge.convertFile(in, out, release);
            mode = nativeMode ? "native" : !workerCp.isEmpty() ? "worker" : "cli";
        }
        System.out.println("OK " + out + " (" + Files.size(out) + " Bytes, " + mode + ")");
    }

    /** Zerlegt einen Klassenpfad-String am plattformspezifischen Trennzeichen (':' bzw. ';'). */
    static List<Path> splitClasspath(String cp) {
        List<Path> entries = new ArrayList<>();
        if (cp == null || cp.isBlank()) return entries;
        for (String part : cp.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!part.isBlank()) entries.add(Path.of(part.trim()));
        }
        return entries;
    }

    private static String required(Map<String, String> a, String key, String env) {
        String v = a.get(key);
        if ((v == null || v.isBlank()) && env != null) v = System.getenv(env);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Parameter --" + key + (env != null ? " oder $" + env : "") + " fehlt");
        }
        return v;
    }

    /** Parst --key value Paare und reine Flags (--native); Flags funktionieren auch am Ende. */
    static Map<String, String> parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) continue;
            String key = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                m.put(key, args[i + 1]);
                i++;
            } else {
                m.put(key, "");
            }
        }
        return m;
    }
}
