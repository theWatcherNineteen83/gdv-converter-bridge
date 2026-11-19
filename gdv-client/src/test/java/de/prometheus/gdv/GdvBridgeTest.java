package de.prometheus.gdv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GdvBridgeTest {

    @TempDir
    Path temp;

    // --- Integrationstests (laufen nur mit GDVTools + JDK 8, sonst assumeTrue-Skip) ---

    @Test
    void konvertiertBeispielXml() throws Exception {
        Path in = Path.of(System.getProperty("gdv.test.xml", ""));
        assumeTrue(Files.exists(in), "gdv.test.xml nicht gesetzt");
        assumeTrue(Files.isExecutable(java8()), "Java 8 Binary fehlt: " + java8());
        Path out = Files.createTempFile("gdv-it-", ".snn");
        try (GdvBridge bridge = new GdvBridge(gdvHome(), java8(), null, workerCp())) {
            bridge.convertFile(in, out, release());
        }
        assertTrue(Files.size(out) > 0, "Ausgabedatei ist leer");
    }

    @Test
    void streamUndDateiLiefernIdentischeBytes() throws Exception {
        Path in = Path.of(System.getProperty("gdv.test.xml", ""));
        assumeTrue(Files.exists(in), "gdv.test.xml nicht gesetzt");
        assumeTrue(!workerCp().isEmpty(), "gdv.worker.cp nicht gesetzt");

        byte[] quelle = Files.readAllBytes(in);
        Path out = Files.createTempFile("gdv-it-file-", ".snn");
        byte[] viaStream;
        try (GdvBridge bridge = new GdvBridge(gdvHome(), java8(), null, workerCp())) {
            bridge.convertFile(in, out, release());
            viaStream = bridge.convertStream(quelle, release(), Direction.of(in.getFileName().toString()));
        }
        assertArrayEquals(Files.readAllBytes(out), viaStream, "Stream- und Dateiweg weichen ab");
    }

    private static Path gdvHome() {
        return Path.of(System.getProperty("gdv.home", "/opt/gdv/GDVTools"));
    }

    private static Path java8() {
        return Path.of(System.getProperty("gdv.java8", "/opt/jdk8/bin/java"));
    }

    private static String release() {
        return System.getProperty("gdv.test.release", "2003");
    }

    private static List<Path> workerCp() {
        String cp = System.getProperty("gdv.worker.cp");
        return Main.splitClasspath(cp);
    }

    // --- Unit-Tests (laufen immer) ---

    @Test
    void libJarsEnumeriertStattWildcard() throws Exception {
        Path gdvHome = temp.resolve("GDVTools");
        Path lib = gdvHome.resolve("lib");
        Files.createDirectories(lib);
        Files.writeString(lib.resolve("b.jar"), "x");
        Files.writeString(lib.resolve("a.jar"), "x");
        Files.writeString(lib.resolve("liesmich.txt"), "x");
        List<Path> jars = GdvBridge.libJars(gdvHome);
        assertEquals(2, jars.size());
        assertEquals("a.jar", jars.get(0).getFileName().toString());
        assertEquals("b.jar", jars.get(1).getFileName().toString());
        for (Path j : jars) {
            assertFalse(j.toString().contains("*"), "Kein Wildcard-Eintrag erlaubt: " + j);
        }
    }

    @Test
    void splitClasspathAmPlatformTrennzeichen() {
        String cp = "a" + File.pathSeparator + "b" + File.pathSeparator + " c ";
        List<Path> entries = Main.splitClasspath(cp);
        assertEquals(List.of(Path.of("a"), Path.of("b"), Path.of("c")), entries);
        assertTrue(Main.splitClasspath(null).isEmpty());
        assertTrue(Main.splitClasspath("  ").isEmpty());
    }

    @Test
    void logFileOptionSchreibtJulLogInDatei() throws Exception {
        Path logFile = temp.resolve("bridge.log");
        Main.setupLogFile(logFile.toString());
        try {
            java.util.logging.Logger.getLogger(GdvBridgeTest.class.getName())
                    .info("Test-Eintrag fuer die Log-Datei");
            for (java.util.logging.Handler h : java.util.logging.Logger.getLogger("").getHandlers()) {
                h.flush();
            }
            assertTrue(Files.exists(logFile), "Log-Datei wurde nicht angelegt");
            assertTrue(Files.size(logFile) > 0, "Log-Datei ist leer");
            assertTrue(Files.readString(logFile).contains("Test-Eintrag"), "Eintrag fehlt in der Log-Datei");
        } finally {
            java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
            for (java.util.logging.Handler h : root.getHandlers()) {
                if (h instanceof java.util.logging.FileHandler) {
                    root.removeHandler(h);
                    h.close();
                }
            }
        }
    }

    @Test
    void setupLogFileOhneAngabeIstNoop() throws Exception {
        Main.setupLogFile(null);
        Main.setupLogFile("  ");
        for (java.util.logging.Handler h : java.util.logging.Logger.getLogger("").getHandlers()) {
            assertFalse(h instanceof java.util.logging.FileHandler, "Ohne Angabe darf kein FileHandler haengen");
        }
    }
}
