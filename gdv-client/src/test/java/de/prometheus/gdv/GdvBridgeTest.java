package de.prometheus.gdv;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integrationstest: braucht GDVTools und ein JDK 8.
 * Aufruf: mvn -Dgdv.home=... -Dgdv.java8=... -Dgdv.test.xml=... test
 */
class GdvBridgeTest {

    @Test
    void konvertiertBeispielXml() throws Exception {
        String gdvHome = System.getProperty("gdv.home");
        String java8 = System.getProperty("gdv.java8");
        String testXml = System.getProperty("gdv.test.xml");
        assumeTrue(gdvHome != null && java8 != null && testXml != null,
                "gdv.home / gdv.java8 / gdv.test.xml nicht gesetzt");

        Path in = Path.of(testXml);
        assumeTrue(Files.exists(in), "Testdatei fehlt: " + in);
        String release = System.getProperty("gdv.test.release", "2003");
        Path out = Files.createTempFile("gdv-it-", ".snn");

        GdvBridge bridge = new GdvBridge(Path.of(gdvHome), Path.of(java8));
        bridge.convertFile(in, out, release);

        assertTrue(Files.size(out) > 0, "Ausgabedatei ist leer");
    }
}
