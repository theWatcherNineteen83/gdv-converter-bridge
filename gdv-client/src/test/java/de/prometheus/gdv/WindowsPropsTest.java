package de.prometheus.gdv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Windows-Befund aus Mail 140: Der Vendor-Parser liest Property-Dateien mit
 * Properties-Style-Escapes. Einfach geschriebene Backslashes werden gefressen
 * ("C:\Tools\konfig\xml" -> "C:Toolskonfigxml"), deshalb schreibt die Bridge
 * verdoppelte Backslashes (wie die mitgelieferte konverter.property des Vendors).
 */
class WindowsPropsTest {

    private static final String WIN_PATH = "C:\\GDV Converter Bridge\\gdvconvert\\GDVTools\\konfig\\xml\\";

    @Test
    void einfacheBackslashesWerdenVomPropertiesParserGefressen() throws Exception {
        // Reproduziert den Fehlmechanismus aus Georgs Stacktrace (FileNotFoundException
        // "GDV Converter BridgegdvconvertGDVToolskonfigxml\gdv2003.xsd").
        Properties p = new Properties();
        p.load(new StringReader("schemaPath=" + WIN_PATH + "\n"));
        assertNotEquals(WIN_PATH, p.getProperty("schemaPath"));
        assertTrue(p.getProperty("schemaPath").contains("BridgegdvconvertGDVToolskonfigxml"));
    }

    @Test
    void escapePropsPathRundlaufDurchPropertiesParser() throws Exception {
        String escaped = GdvBridge.escapePropsPath(WIN_PATH);
        Properties p = new Properties();
        p.load(new StringReader("schemaPath=" + escaped + "\n"));
        assertEquals(WIN_PATH, p.getProperty("schemaPath"));
    }

    @Test
    void propsDirLinuxBleibtUnveraendert(@TempDir Path tmp) {
        Path dir = tmp.resolve("konfig").resolve("xml");
        String s = GdvBridge.propsDir(dir);
        assertTrue(s.endsWith("/"));
        assertFalse(s.contains("\\"));
        assertEquals(dir + "/", s);
    }

    @Test
    void propsDirHaengtSeparatorAnUndEscaped() {
        // Plattformunabhaengig: der String-Pfad enthaelt Backslashes (auf Linux ist das
        // ein gueltiger relativer Dateiname); propsDir muss sie verdoppeln.
        Path fakeWin = Path.of("C:\\GDV Tools\\konfig\\xml");
        String s = GdvBridge.propsDir(fakeWin);
        assertTrue(s.contains("\\\\GDV Tools\\\\konfig\\\\xml"));
        assertFalse(s.replace("\\\\", "").contains("\\"));
    }

    @Test
    void mainJarFindetVersionierteVariante(@TempDir Path tmp) throws Exception {
        Path versioned = tmp.resolve("GDV-Schadennetz-3.2.4.jar");
        Files.writeString(versioned, "x");
        assertEquals(versioned, GdvBridge.mainJar(tmp));
    }

    @Test
    void mainJarFindetVersionierteVarianteInLib(@TempDir Path tmp) throws Exception {
        Path lib = Files.createDirectories(tmp.resolve("lib"));
        Path versioned = lib.resolve("GDV-Schadennetz-2026.11.jar");
        Files.writeString(versioned, "x");
        assertEquals(versioned, GdvBridge.mainJar(tmp));
    }

    @Test
    void mainJarExakterNameHatVorrang(@TempDir Path tmp) throws Exception {
        Path exact = tmp.resolve("GDV-Schadennetz.jar");
        Files.writeString(exact, "x");
        Files.writeString(tmp.resolve("GDV-Schadennetz-9.9.jar"), "x");
        assertEquals(exact, GdvBridge.mainJar(tmp));
    }

    @Test
    void mainJarOhneTrefferWirftSinnvoll(@TempDir Path tmp) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> GdvBridge.mainJar(tmp));
        assertTrue(e.getMessage().contains("GDV-Schadennetz"));
    }
}
