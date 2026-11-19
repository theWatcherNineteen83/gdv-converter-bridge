package de.prometheus.gdv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests der GUI-Kernlogik (Swing-frei, headless lauffaehig). */
class TestGuiLogicTest {

    @Test
    void sha256BekannterVektor() {
        // SHA-256 von "abc"
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                TestGui.sha256("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void compareIdentischUndAbweichend() {
        var a = new TestGui.ModeResult("native", true, 10, "aa", 5, "x", null);
        var b = new TestGui.ModeResult("worker", true, 10, "aa", 6, "y", null);
        assertTrue(TestGui.compare(List.of(a, b)).contains("byte-identisch"));
        var c = new TestGui.ModeResult("cli", true, 11, "bb", 7, "z", null);
        String s = TestGui.compare(List.of(a, b, c));
        assertTrue(s.contains("2 unterschiedliche Ergebnisse"));
        assertTrue(TestGui.compare(List.of()).contains("kein erfolgreicher Weg"));
    }

    @Test
    void runAllValidiertPfade(@TempDir Path tmp) throws Exception {
        TestGui.Config c = new TestGui.Config();
        c.gdvHome = tmp; // konfig/ fehlt
        c.input = tmp.resolve("x.xml");
        assertThrows(java.io.IOException.class, () -> TestGui.runAll(c, s -> { }));

        Files.createDirectories(tmp.resolve("konfig"));
        c.gdvHome = tmp;
        assertThrows(java.io.IOException.class, () -> TestGui.runAll(c, s -> { })); // input fehlt

        Files.writeString(c.input, "<a/>");
        c.release = " ";
        assertThrows(java.io.IOException.class, () -> TestGui.runAll(c, s -> { })); // release fehlt

        c.release = "2003";
        c.input = tmp.resolve("x.txt");
        Files.writeString(c.input, "x");
        assertThrows(IllegalArgumentException.class, () -> TestGui.runAll(c, s -> { })); // Endung
    }

    @Test
    void workerModusOhneKlassenpfadWirftSinnvoll(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("konfig"));
        Path in = tmp.resolve("in.xml");
        Files.writeString(in, "<a/>");
        TestGui.Config c = new TestGui.Config();
        c.gdvHome = tmp;
        c.input = in;
        c.outDir = tmp.resolve("out");
        c.release = "2003";
        c.modes = List.of("worker");
        List<TestGui.ModeResult> r = TestGui.runAll(c, s -> { });
        assertEquals(1, r.size());
        assertFalse(r.get(0).ok());
        assertTrue(r.get(0).detail().contains("Worker-Klassenpfad fehlt"));
    }
}
