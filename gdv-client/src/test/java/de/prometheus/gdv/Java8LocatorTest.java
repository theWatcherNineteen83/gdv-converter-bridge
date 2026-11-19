package de.prometheus.gdv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class Java8LocatorTest {

    @TempDir
    Path temp;

    @Test
    void expliziteDateiWirdUebernommen() throws Exception {
        Path java = temp.resolve("java");
        Files.writeString(java, "#!/bin/sh\n");
        assertEquals(java.toAbsolutePath(), Java8Locator.resolve(java, temp));
    }

    @Test
    void verzeichnisWirdZuBinJavaAufgeloest() throws Exception {
        Path jre = temp.resolve("jre8");
        Path bin = jre.resolve("bin");
        Files.createDirectories(bin);
        Path java = bin.resolve("java");
        Files.writeString(java, "#!/bin/sh\n");
        assertEquals(java.toAbsolutePath(), Java8Locator.resolve(jre, temp));
    }

    @Test
    void standardAblageNebenGdvHomeWirdGefunden() throws Exception {
        String saved = System.clearProperty("gdv.java8");
        try {
            Path gdvHome = temp.resolve("GDVTools");
            Path bin = gdvHome.resolve("jre8").resolve("bin");
            Files.createDirectories(bin);
            Path java = bin.resolve("java");
            Files.writeString(java, "#!/bin/sh\n");
            assertEquals(java.toAbsolutePath(), Java8Locator.resolve(null, gdvHome));
        } finally {
            if (saved != null) System.setProperty("gdv.java8", saved);
        }
    }

    @Test
    void nichtsGefundenKlareFehlermeldung() {
        String saved = System.clearProperty("gdv.java8");
        try {
            Path gdvHome = temp.resolve("leer");
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> Java8Locator.resolve(null, gdvHome));
            assertTrue(e.getMessage().contains("GDV_JAVA8"));
            assertTrue(e.getMessage().contains("--native"));
        } finally {
            if (saved != null) System.setProperty("gdv.java8", saved);
        }
    }
}
