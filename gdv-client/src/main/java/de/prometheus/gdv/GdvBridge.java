package de.prometheus.gdv;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Bruecke zum GDV-XMLKonverter.
 *
 * Drei Betriebsarten:
 *  - native:   GDV-Klassen laufen im Java-21-Prozess (kein Java 8 noetig, in-memory)
 *  - worker:   persistenter Java-8-Prozess mit binaerem Protokoll (in-memory)
 *  - fallback: Java-8-Kindprozess pro Aufruf ueber die GDV-CLI (Temp-Dateien)
 *
 * Achtung: Der native Modus schreibt bewusst in einen ByteArrayOutputStream. Direktes
 * Schreiben in einen FileOutputStream liefert auf Java 21 eine leere Datei.
 *
 * Windows/Linux: Klassenpfade werden ohne Wildcards aufgebaut (die lib/*.jar werden
 * einzeln enumeriert - Wildcards expandiert nur der java-Launcher, nicht jeder
 * ClassLoader/Pfadkonsument), Trennzeichen ist immer File.pathSeparator, das
 * Java-8-Binary wird plattformgerecht aufgeloest (java.exe unter Windows).
 *
 * Logging: java.util.logging. Fehler werden mit Stacktrace protokolliert (SEVERE);
 * in Spring Boot ueber jul-to-slf4j an das App-Logging anbindbar.
 */
public final class GdvBridge implements Closeable {

    private static final Logger LOG = Logger.getLogger(GdvBridge.class.getName());

    private enum Mode { NATIVE, WORKER, CLI }

    private final Path gdvHome;
    private final Path java8;
    private final Path propertyFile;
    private final List<Path> workerClasspath;
    private final String workerMainClass;
    private final Mode mode;

    private GdvWorker worker;
    private NativeGdv nativeGdv;
    private Path generatedProps;

    public GdvBridge(Path gdvHome, Path java8) {
        this(gdvHome, java8, null, List.of(), null, Mode.CLI);
    }

    public GdvBridge(Path gdvHome, Path java8, Path propertyFile, List<Path> workerClasspath) {
        this(gdvHome, java8, propertyFile, workerClasspath, null, Mode.WORKER);
    }

    /**
     * @param workerMainClass vollqualifizierter Name der Worker-Hauptklasse; null =
     *                        dynamische Suche im workerClasspath (siehe WorkerMainFinder)
     */
    public GdvBridge(Path gdvHome, Path java8, Path propertyFile, List<Path> workerClasspath,
                     String workerMainClass) {
        this(gdvHome, java8, propertyFile, workerClasspath, workerMainClass, Mode.WORKER);
    }

    private GdvBridge(Path gdvHome, Path java8, Path propertyFile, List<Path> workerClasspath,
                      String workerMainClass, Mode mode) {
        this.gdvHome = gdvHome.toAbsolutePath();
        this.propertyFile = propertyFile;
        this.workerClasspath = workerClasspath == null ? List.of() : List.copyOf(workerClasspath);
        this.workerMainClass = workerMainClass;
        this.mode = mode == Mode.WORKER && this.workerClasspath.isEmpty() ? Mode.CLI : mode;
        if (this.mode != Mode.NATIVE) {
            this.java8 = Java8Locator.resolve(java8, this.gdvHome);
            if (!Files.isExecutable(this.java8)) {
                throw new IllegalArgumentException("Java 8 Binary nicht ausfuehrbar: " + this.java8);
            }
        } else {
            this.java8 = null;
        }
        if (!Files.isDirectory(this.gdvHome.resolve("konfig"))) {
            throw new IllegalArgumentException("GDVTools-Verzeichnis ungueltig: " + this.gdvHome);
        }
        LOG.info(() -> "GdvBridge initialisiert: modus=" + this.mode + ", gdvHome=" + this.gdvHome
                + (this.java8 != null ? ", java8=" + this.java8 : ""));
    }

    /** Native Modus: GDV-Klassen im Java-21-Prozess, kein Java 8 erforderlich. */
    public static GdvBridge nativeMode(Path gdvHome) {
        return new GdvBridge(gdvHome, null, null, List.of(), null, Mode.NATIVE);
    }

    public void convertFile(Path in, Path out, String release) throws Exception {
        LOG.info(() -> "Konvertiere Datei " + in + " -> " + out + " (Release " + release + ")");
        try {
            Direction dir = Direction.of(in.getFileName().toString());
            byte[] result = convertStream(Files.readAllBytes(in), release, dir);
            Path parent = out.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(out, result);
            LOG.info(() -> "Konvertierung erfolgreich: " + out + " (" + result.length + " Bytes)");
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Datei-Konvertierung fehlgeschlagen: " + in + " -> " + out
                    + " (Release " + release + ")", e);
            throw e;
        }
    }

    public byte[] convertStream(byte[] data, String release, Direction dir) throws Exception {
        try {
            switch (mode) {
                case NATIVE:
                    return nativeConverter().convert(data, release, dir);
                case WORKER:
                    return workerConverter().convert(data, release, dir);
                default:
                    return convertViaCli(data, release, dir);
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Stream-Konvertierung fehlgeschlagen (Modus " + mode
                    + ", Release " + release + ", Richtung " + dir + ", " + data.length + " Bytes Eingabe)", e);
            throw e;
        }
    }

    private NativeGdv nativeConverter() throws Exception {
        if (nativeGdv == null) {
            nativeGdv = new NativeGdv(gdvHome);
        }
        return nativeGdv;
    }

    private GdvWorker workerConverter() throws IOException {
        if (worker == null) {
            List<Path> cp = new ArrayList<>(workerClasspath);
            cp.addAll(libJars(gdvHome));
            cp.add(mainJar(gdvHome));
            worker = GdvWorker.start(java8, cp, propertiesFile(), workerMainClass, gdvHome);
        }
        return worker;
    }

    /**
     * Enumeriert die Jar-Dateien im lib-Verzeichnis von GDVTools (statt eines
     * Wildcard-Eintrags "lib/*"). Funktioniert dadurch unter Windows und Linux und
     * mit jedem ClassLoader bzw. ProcessBuilder-Klassenpfad.
     */
    static List<Path> libJars(Path gdvHome) {
        Path lib = gdvHome.resolve("lib");
        List<Path> jars = new ArrayList<>();
        if (!Files.isDirectory(lib)) {
            LOG.warning(() -> "GDVTools lib-Verzeichnis fehlt: " + lib);
            return jars;
        }
        try (Stream<Path> s = Files.list(lib)) {
            s.filter(p -> p.toString().toLowerCase().endsWith(".jar"))
                    .sorted()
                    .forEach(jars::add);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "lib-Verzeichnis nicht lesbar: " + lib, e);
        }
        if (jars.isEmpty()) {
            LOG.warning(() -> "Keine Jars in " + lib + " gefunden");
        }
        return jars;
    }

    private byte[] convertViaCli(byte[] data, String release, Direction dir) throws IOException {
        Path work = Files.createTempDirectory("gdv-work-");
        try {
            Path localIn = work.resolve("input" + dir.inExt());
            Files.write(localIn, data);
            Path localOut = work.resolve("input" + dir.outExt());
            Result r = run(localIn, release, propertiesFile());
            if (r.exit != 0 || !Files.exists(localOut) || Files.size(localOut) == 0) {
                IOException ex = new IOException("Konvertierung fehlgeschlagen (exit=" + r.exit + "):\n" + r.log);
                LOG.log(Level.SEVERE, "GDV-CLI fehlgeschlagen (Release " + release + ")", ex);
                throw ex;
            }
            return Files.readAllBytes(localOut);
        } finally {
            deleteRecursively(work);
        }
    }

    private Path propertiesFile() throws IOException {
        if (propertyFile != null) return propertyFile;
        if (generatedProps != null && Files.exists(generatedProps)) return generatedProps;
        Path props = Files.createTempFile("gdv-", ".properties");
        props.toFile().deleteOnExit();
        String content = "[GDV]\n"
                + "schemaPath=" + propsDir(gdvHome.resolve("konfig").resolve("xml")) + "\n"
                + "workingPath=" + propsDir(gdvHome.resolve("konfig")) + "\n"
                + "schemaPrefix=xsd:\n"
                + "nameSpaceURL=http://www.w3.org/2001/XMLSchema\n";
        Files.writeString(props, content, StandardCharsets.UTF_8);
        generatedProps = props;
        return props;
    }

    /**
     * Windows-sichere Pfad-Schreibweise fuer GDV-Property-Dateien: Backslashes werden
     * verdoppelt. Der Vendor-Parser liest Properties-Style-Escapes; einfache Backslashes
     * werden sonst gefressen (C:\Tools\konfig wird zu C:Toolskonfig). Die mitgelieferte
     * konfig/konverter.property des Vendors nutzt selbst doppelte Backslashes
     * (schemaPath=konfig\\xml\\). Auf Linux unveraendert (Forward-Slashes).
     */
    static String propsDir(Path dir) {
        String s = dir.toString();
        if (!s.endsWith("/") && !s.endsWith("\\")) s += File.separator;
        return escapePropsPath(s);
    }

    /** Verdoppelt Backslashes fuer Properties-Style-Parser (Plattform-unabhaengig testbar). */
    static String escapePropsPath(String s) {
        return s.replace("\\", "\\\\");
    }

    /**
     * Findet das GDV-Hauptjar: exakt GDV-Schadennetz.jar, sonst versionierte Varianten
     * (GDV-Schadennetz*.jar) im GDVTools-Verzeichnis oder in lib/. Beim Vendor liegen
     * die Jars teils mit Versionsnummern im Dateinamen vor.
     */
    static Path mainJar(Path gdvHome) {
        Path exact = gdvHome.resolve("GDV-Schadennetz.jar");
        if (Files.isRegularFile(exact)) return exact;
        List<Path> candidates = new ArrayList<>();
        for (Path dir : List.of(gdvHome, gdvHome.resolve("lib"))) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> {
                    String n = p.getFileName().toString().toLowerCase();
                    return n.startsWith("gdv-schadennetz") && n.endsWith(".jar");
                }).sorted().forEach(candidates::add);
            } catch (IOException e) {
                LOG.log(Level.SEVERE, "Jar-Suche fehlgeschlagen: " + dir, e);
            }
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("GDV-Jar fehlt: GDV-Schadennetz.jar (oder GDV-Schadennetz*.jar) in "
                    + gdvHome + " bzw. " + gdvHome.resolve("lib"));
        }
        if (candidates.size() > 1) {
            LOG.warning(() -> "Mehrere GDV-Jars gefunden, nehme " + candidates.get(0) + ": " + candidates);
        } else {
            LOG.info(() -> "GDV-Jar (versioniert): " + candidates.get(0));
        }
        return candidates.get(0);
    }

    private Result run(Path input, String release, Path props) throws IOException {
        StringBuilder classpath = new StringBuilder();
        for (Path jar : libJars(gdvHome)) {
            if (classpath.length() > 0) classpath.append(java.io.File.pathSeparator);
            classpath.append(jar);
        }
        if (classpath.length() > 0) classpath.append(java.io.File.pathSeparator);
        classpath.append(mainJar(gdvHome));
        List<String> cmd = List.of(java8.toString(), "-cp", classpath.toString(), "sn.SN_XMLKonverter",
                input.toString(), "5", release, props.toString());
        LOG.fine(() -> "GDV-CLI: " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(gdvHome.toFile()).redirectErrorStream(true);
        Process p = pb.start();
        String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit;
        try {
            exit = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        if (exit != 0) {
            LOG.severe(() -> "GDV-CLI exit=" + exit + ", Ausgabe:\n" + log);
        }
        return new Result(exit, log);
    }

    private static void deleteRecursively(Path p) {
        try (var s = Files.walk(p)) {
            s.sorted((a, b) -> b.compareTo(a)).forEach(x -> {
                try { Files.deleteIfExists(x); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    @Override
    public void close() {
        if (worker != null) {
            worker.close();
            worker = null;
        }
        if (nativeGdv != null) {
            nativeGdv.close();
            nativeGdv = null;
        }
    }

    private record Result(int exit, String log) { }
}
