package de.prometheus.gdv;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GdvBridgeTest {

    @Test
    void konvertiertBeispielXml() throws Exception {
        Path in = Path.of(System.getProperty("gdv.test.xml", ""));
        assumeTrue(Files.exists(in), "gdv.test.xml nicht gesetzt");
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
        return cp == null || cp.isBlank() ? List.of() : List.of(Path.of(cp));
    }
}
