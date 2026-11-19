package de.prometheus.gdv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class WorkerMainFinderTest {

    @TempDir
    Path temp;

    @Test
    void findetKlasseInVerzeichnisClasspath() throws Exception {
        Path classes = temp.resolve("classes");
        Path pkg = classes.resolve("com").resolve("huk").resolve("gdv").resolve("worker");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("WorkerMain.class"), "dummy");
        assertEquals("com.huk.gdv.worker.WorkerMain", WorkerMainFinder.find(List.of(classes)));
    }

    @Test
    void findetKlasseInJar() throws Exception {
        Path jar = temp.resolve("worker-relocated.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            z.putNextEntry(new ZipEntry("de/other/pkg/WorkerMain.class"));
            z.write(new byte[]{1, 2, 3});
            z.closeEntry();
        }
        assertEquals("de.other.pkg.WorkerMain", WorkerMainFinder.find(List.of(jar)));
    }

    @Test
    void mehrereKandidatenOriginalGewinnt() throws Exception {
        Path a = temp.resolve("a");
        Path b = temp.resolve("b");
        Files.createDirectories(a.resolve("de/prometheus/gdv/worker"));
        Files.writeString(a.resolve("de/prometheus/gdv/worker/WorkerMain.class"), "x");
        Files.createDirectories(b.resolve("com/other"));
        Files.writeString(b.resolve("com/other/WorkerMain.class"), "x");
        assertEquals(WorkerMainFinder.DEFAULT_MAIN, WorkerMainFinder.find(List.of(b, a)));
    }

    @Test
    void mehrereKandidatenOhneOriginalFehlerMitKandidatenliste() throws Exception {
        Path a = temp.resolve("a");
        Path b = temp.resolve("b");
        Files.createDirectories(a.resolve("com/one"));
        Files.writeString(a.resolve("com/one/WorkerMain.class"), "x");
        Files.createDirectories(b.resolve("com/two"));
        Files.writeString(b.resolve("com/two/WorkerMain.class"), "x");
        IOException e = assertThrows(IOException.class, () -> WorkerMainFinder.find(List.of(a, b)));
        assertTrue(e.getMessage().contains("com.one.WorkerMain"));
        assertTrue(e.getMessage().contains("com.two.WorkerMain"));
    }

    @Test
    void keinTrefferKlareFehlermeldung() throws Exception {
        Path empty = temp.resolve("empty");
        Files.createDirectories(empty);
        IOException e = assertThrows(IOException.class, () -> WorkerMainFinder.find(List.of(empty)));
        assertTrue(e.getMessage().contains("gdv.worker.main"));
    }
}
