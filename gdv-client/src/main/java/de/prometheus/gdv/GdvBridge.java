package de.prometheus.gdv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Ruft den GDV-XMLKonverter in einem Java-8-Prozess auf.
 */
public final class GdvBridge {

    private final Path gdvHome;
    private final Path java8;
    private final Path propertyFile;

    public GdvBridge(Path gdvHome, Path java8) {
        this(gdvHome, java8, null);
    }

    public GdvBridge(Path gdvHome, Path java8, Path propertyFile) {
        this.gdvHome = gdvHome.toAbsolutePath();
        this.java8 = java8.toAbsolutePath();
        this.propertyFile = propertyFile;
        if (!Files.isExecutable(this.java8)) {
            throw new IllegalArgumentException("Java 8 Binary nicht ausfuehrbar: " + this.java8);
        }
        if (!Files.isDirectory(this.gdvHome.resolve("konfig"))) {
            throw new IllegalArgumentException("GDVTools-Verzeichnis ungueltig: " + this.gdvHome);
        }
    }

    /** Konvertiert eine Datei; die Ausgabedatei wird aus der Eingabeendung abgeleitet. */
    public void convertFile(Path in, Path out, String release) throws IOException, InterruptedException {
        Direction dir = Direction.of(in.getFileName().toString());
        Path work = Files.createTempDirectory("gdv-work-");
        Path props = writeProperties(work);
        try {
            Path localIn = work.resolve("input" + dir.inExt());
            Files.copy(in, localIn, StandardCopyOption.REPLACE_EXISTING);
            Path localOut = work.resolve("input" + dir.outExt());
            Result r = run(localIn, release, props);
            if (r.exit != 0 || !Files.exists(localOut) || Files.size(localOut) == 0) {
                throw new IOException("Konvertierung fehlgeschlagen (exit=" + r.exit + "):\n" + r.log);
            }
            Files.createDirectories(out.toAbsolutePath().getParent());
            Files.copy(localOut, out, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            deleteRecursively(work);
        }
    }

    /** Konvertiert Bytes und gibt das Ergebnis zurück (Stufe 2a: ueber Temp-Dateien). */
    public byte[] convertStream(byte[] in, String release, Direction dir) throws IOException, InterruptedException {
        Path work = Files.createTempDirectory("gdv-work-");
        Path props = writeProperties(work);
        try {
            Path localIn = work.resolve("input" + dir.inExt());
            Files.write(localIn, in);
            Path localOut = work.resolve("input" + dir.outExt());
            Result r = run(localIn, release, props);
            if (r.exit != 0 || !Files.exists(localOut)) {
                throw new IOException("Konvertierung fehlgeschlagen (exit=" + r.exit + "):\n" + r.log);
            }
            return Files.readAllBytes(localOut);
        } finally {
            deleteRecursively(work);
        }
    }

    private Result run(Path input, String release, Path props) throws IOException, InterruptedException {
        String classpath = gdvHome.resolve("lib").toString() + "/*"
                + java.io.File.pathSeparator + gdvHome.resolve("GDV-Schadennetz.jar");
        List<String> cmd = new ArrayList<>();
        cmd.add(java8.toString());
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add("sn.SN_XMLKonverter");
        cmd.add(input.toString());
        cmd.add("5");
        cmd.add(release);
        cmd.add(props.toString());

        ProcessBuilder pb = new ProcessBuilder(cmd).directory(gdvHome.toFile()).redirectErrorStream(true);
        Process p = pb.start();
        String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();
        return new Result(exit, log);
    }

    private Path writeProperties(Path work) throws IOException {
        if (propertyFile != null) return propertyFile;
        Path props = work.resolve("konverter.properties");
        String content = "[GDV]\n"
                + "schemaPath=" + gdvHome.resolve("konfig").resolve("xml") + "/\n"
                + "workingPath=" + gdvHome.resolve("konfig") + "/\n"
                + "schemaPrefix=xsd:\n"
                + "nameSpaceURL=http://www.w3.org/2001/XMLSchema\n";
        Files.writeString(props, content, StandardCharsets.UTF_8);
        return props;
    }

    private static void deleteRecursively(Path p) {
        try (var s = Files.walk(p)) {
            s.sorted((a, b) -> b.compareTo(a)).forEach(x -> {
                try { Files.deleteIfExists(x); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private record Result(int exit, String log) { }
}
