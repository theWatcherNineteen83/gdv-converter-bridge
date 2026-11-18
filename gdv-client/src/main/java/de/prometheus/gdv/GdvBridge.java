package de.prometheus.gdv;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Bruecke zum GDV-XMLKonverter. Der Konverter laeuft immer in einem Java-8-Prozess.
 *
 * Streams laufen ueber einen persistenten Worker (binäres Protokoll, kein Temp-File).
 * Ist kein Worker-Classpath gesetzt, faellt die Stream-Konvertierung auf Temp-Dateien
 * zurueck (langsamer, aber funktional identisch).
 */
public final class GdvBridge implements Closeable {

    private final Path gdvHome;
    private final Path java8;
    private final Path propertyFile;
    private final List<Path> workerClasspath;
    private GdvWorker worker;

    public GdvBridge(Path gdvHome, Path java8) {
        this(gdvHome, java8, null, List.of());
    }

    public GdvBridge(Path gdvHome, Path java8, Path propertyFile, List<Path> workerClasspath) {
        this.gdvHome = gdvHome.toAbsolutePath();
        this.java8 = java8.toAbsolutePath();
        this.propertyFile = propertyFile;
        this.workerClasspath = workerClasspath == null ? List.of() : List.copyOf(workerClasspath);
        if (!Files.isExecutable(this.java8)) {
            throw new IllegalArgumentException("Java 8 Binary nicht ausfuehrbar: " + this.java8);
        }
        if (!Files.isDirectory(this.gdvHome.resolve("konfig"))) {
            throw new IllegalArgumentException("GDVTools-Verzeichnis ungueltig: " + this.gdvHome);
        }
    }

    /** Konvertiert eine Datei; Richtung ergibt sich aus der Endung. */
    public void convertFile(Path in, Path out, String release) throws IOException {
        Direction dir = Direction.of(in.getFileName().toString());
        byte[] result = convertStream(Files.readAllBytes(in), release, dir);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.write(out, result);
    }

    /** Konvertiert Bytes ohne Temp-Datei (Worker) bzw. ueber Temp-Dateien (Fallback). */
    public byte[] convertStream(byte[] data, String release, Direction dir) throws IOException {
        if (!workerClasspath.isEmpty()) {
            return worker().convert(data, release, dir);
        }
        return convertViaTempFiles(data, release, dir);
    }

    private GdvWorker worker() throws IOException {
        if (worker == null) {
            List<Path> cp = new ArrayList<>(workerClasspath);
            cp.add(gdvHome.resolve("lib").resolve("*"));
            cp.add(gdvHome.resolve("GDV-Schadennetz.jar"));
            worker = GdvWorker.start(java8, cp, propertiesFile());
        }
        return worker;
    }

    private byte[] convertViaTempFiles(byte[] data, String release, Direction dir) throws IOException {
        Path work = Files.createTempDirectory("gdv-work-");
        try {
            Path localIn = work.resolve("input" + dir.inExt());
            Files.write(localIn, data);
            Path localOut = work.resolve("input" + dir.outExt());
            Result r = run(localIn, release, propertiesFile());
            if (r.exit != 0 || !Files.exists(localOut)) {
                throw new IOException("Konvertierung fehlgeschlagen (exit=" + r.exit + "):\n" + r.log);
            }
            return Files.readAllBytes(localOut);
        } finally {
            deleteRecursively(work);
        }
    }

    private Path propertiesFile() throws IOException {
        if (propertyFile != null) return propertyFile;
        Path props = Files.createTempFile("gdv-", ".properties");
        props.toFile().deleteOnExit();
        String content = "[GDV]\n"
                + "schemaPath=" + gdvHome.resolve("konfig").resolve("xml") + "/\n"
                + "workingPath=" + gdvHome.resolve("konfig") + "/\n"
                + "schemaPrefix=xsd:\n"
                + "nameSpaceURL=http://www.w3.org/2001/XMLSchema\n";
        Files.writeString(props, content, StandardCharsets.UTF_8);
        return props;
    }

    private Result run(Path input, String release, Path props) throws IOException {
        String classpath = gdvHome.resolve("lib").toString() + "/*"
                + java.io.File.pathSeparator + gdvHome.resolve("GDV-Schadennetz.jar");
        List<String> cmd = List.of(java8.toString(), "-cp", classpath, "sn.SN_XMLKonverter",
                input.toString(), "5", release, props.toString());
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(gdvHome.toFile()).redirectErrorStream(true);
        Process p = pb.start();
        java.io.PrintStream origOut = System.out;
        String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit;
        try {
            exit = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
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
    }

    private record Result(int exit, String log) { }
}
