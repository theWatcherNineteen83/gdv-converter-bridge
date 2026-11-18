package de.prometheus.gdv;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

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
 */
public final class GdvBridge implements Closeable {

    private enum Mode { NATIVE, WORKER, CLI }

    private final Path gdvHome;
    private final Path java8;
    private final Path propertyFile;
    private final List<Path> workerClasspath;
    private final Mode mode;

    private GdvWorker worker;
    private NativeGdv nativeGdv;

    public GdvBridge(Path gdvHome, Path java8) {
        this(gdvHome, java8, null, List.of(), Mode.CLI);
    }

    public GdvBridge(Path gdvHome, Path java8, Path propertyFile, List<Path> workerClasspath) {
        this(gdvHome, java8, propertyFile, workerClasspath, Mode.WORKER);
    }

    private GdvBridge(Path gdvHome, Path java8, Path propertyFile, List<Path> workerClasspath, Mode mode) {
        this.gdvHome = gdvHome.toAbsolutePath();
        this.java8 = java8 == null ? null : java8.toAbsolutePath();
        this.propertyFile = propertyFile;
        this.workerClasspath = workerClasspath == null ? List.of() : List.copyOf(workerClasspath);
        this.mode = mode == Mode.WORKER && this.workerClasspath.isEmpty() ? Mode.CLI : mode;
        if (this.mode != Mode.NATIVE) {
            if (this.java8 == null || !Files.isExecutable(this.java8)) {
                throw new IllegalArgumentException("Java 8 Binary nicht ausfuehrbar: " + this.java8);
            }
        }
        if (!Files.isDirectory(this.gdvHome.resolve("konfig"))) {
            throw new IllegalArgumentException("GDVTools-Verzeichnis ungueltig: " + this.gdvHome);
        }
    }

    /** Native Modus: GDV-Klassen im Java-21-Prozess, kein Java 8 erforderlich. */
    public static GdvBridge nativeMode(Path gdvHome) {
        return new GdvBridge(gdvHome, null, null, List.of(), Mode.NATIVE);
    }

    public void convertFile(Path in, Path out, String release) throws Exception {
        Direction dir = Direction.of(in.getFileName().toString());
        byte[] result = convertStream(Files.readAllBytes(in), release, dir);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.write(out, result);
    }

    public byte[] convertStream(byte[] data, String release, Direction dir) throws Exception {
        switch (mode) {
            case NATIVE:
                return nativeConverter().convert(data, release, dir);
            case WORKER:
                return workerConverter().convert(data, release, dir);
            default:
                return convertViaCli(data, release, dir);
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
            cp.add(gdvHome.resolve("lib").resolve("*"));
            cp.add(gdvHome.resolve("GDV-Schadennetz.jar"));
            worker = GdvWorker.start(java8, cp, propertiesFile());
        }
        return worker;
    }

    private byte[] convertViaCli(byte[] data, String release, Direction dir) throws IOException {
        Path work = Files.createTempDirectory("gdv-work-");
        try {
            Path localIn = work.resolve("input" + dir.inExt());
            Files.write(localIn, data);
            Path localOut = work.resolve("input" + dir.outExt());
            Result r = run(localIn, release, propertiesFile());
            if (r.exit != 0 || !Files.exists(localOut) || Files.size(localOut) == 0) {
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
        if (nativeGdv != null) {
            nativeGdv.close();
            nativeGdv = null;
        }
    }

    private record Result(int exit, String log) { }
}
