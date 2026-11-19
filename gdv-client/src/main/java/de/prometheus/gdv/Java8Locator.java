package de.prometheus.gdv;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Loest das Java-8-Binary plattformunabhaengig auf (Windows: java.exe, Linux/macOS: java).
 *
 * Reihenfolge:
 *  1. expliziter Pfad (Datei ODER Verzeichnis -> dann bin/java[.exe]); auf Windows wird
 *     bei fehlender Datei automatisch .exe angehaengt
 *  2. System-Property {@code gdv.java8}
 *  3. Umgebungsvariable {@code GDV_JAVA8}
 *  4. Standard-Ablagen relativ zum GDVTools-Verzeichnis und zum Arbeitsverzeichnis:
 *     {@code jre8/}, {@code jre/}, {@code java8/} (jeweils mit {@code bin/})
 *
 * Empfehlung fuer Spring Boot: das JRE ausserhalb des Fat-Jars ablegen (z. B.
 * {@code <app-verzeichnis>/jre8/} oder ein fester Pfad wie {@code C:\gdv\jre8}) und per
 * {@code GDV_JAVA8} oder {@code -Dgdv.java8} konfigurieren. Ein im Fat-Jar eingebettetes
 * JRE kann nicht als Prozess gestartet werden. Der native Modus braucht gar kein Java 8.
 */
public final class Java8Locator {

    private static final Logger LOG = Logger.getLogger(Java8Locator.class.getName());
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final String EXE = WINDOWS ? "java.exe" : "java";

    private Java8Locator() { }

    public static Path resolve(Path explicit, Path gdvHome) {
        List<String> tried = new ArrayList<>();
        Path p = fromCandidate(explicit, tried);
        if (p == null) p = fromCandidate(pathOrNull(System.getProperty("gdv.java8")), tried);
        if (p == null) p = fromCandidate(pathOrNull(System.getenv("GDV_JAVA8")), tried);
        if (p == null) {
            for (Path base : new Path[]{ gdvHome, Path.of(System.getProperty("user.dir")) }) {
                if (base == null) continue;
                for (String dir : new String[]{ "jre8", "jre", "java8" }) {
                    p = fromCandidate(base.resolve(dir), tried);
                    if (p != null) break;
                }
                if (p != null) break;
            }
        }
        if (p == null) {
            throw new IllegalArgumentException("Kein Java-8-Binary gefunden. Gesucht: " + tried
                    + " - bitte --java8 / -Dgdv.java8 / $GDV_JAVA8 setzen oder ein JRE als"
                    + " jre8/ neben GDVTools bzw. im Arbeitsverzeichnis ablegen."
                    + " Hinweis: --native benoetigt kein Java 8.");
        }
        final Path resolved = p;
        LOG.fine(() -> "Java-8-Binary: " + resolved);
        return resolved;
    }

    private static Path fromCandidate(Path candidate, List<String> tried) {
        if (candidate == null) return null;
        Path c = candidate.toAbsolutePath();
        tried.add(c.toString());
        if (Files.isDirectory(c)) {
            Path bin = c.resolve("bin").resolve(EXE);
            tried.add(bin.toString());
            if (Files.isRegularFile(bin)) return bin;
            Path direct = c.resolve(EXE);
            tried.add(direct.toString());
            if (Files.isRegularFile(direct)) return direct;
            return null;
        }
        if (Files.isRegularFile(c)) return c;
        if (WINDOWS && !c.toString().endsWith(".exe")) {
            Path exe = Path.of(c + ".exe");
            tried.add(exe.toString());
            if (Files.isRegularFile(exe)) return exe;
        }
        return null;
    }

    private static Path pathOrNull(String s) {
        return s == null || s.isBlank() ? null : Path.of(s);
    }
}
