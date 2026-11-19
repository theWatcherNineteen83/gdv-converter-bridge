package de.prometheus.gdv;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Findet die Worker-Hauptklasse dynamisch im von aussen mitgegebenen Klassenpfad.
 *
 * Hintergrund: Die Worker-Klassen koennen in andere Projekte/Packages verschoben werden.
 * Der Client darf deshalb keinen festen Package-Namen voraussetzen. Gesucht wird jede
 * Klasse mit dem einfachen Namen {@code WorkerMain} in Verzeichnis- und Jar-Eintraegen
 * des Klassenpfads. Der vollqualifizierte Name wird aus dem Pfad abgeleitet.
 *
 * Prioritaet bei mehreren Treffern:
 *  1. explizite Vorgabe (Parameter oder System-Property {@code gdv.worker.main})
 *  2. das Original-Package {@code de.prometheus.gdv.worker.WorkerMain}
 *  3. genau ein Treffer mit {@code gdv} im Package-Namen
 *  4. sonst Fehler mit Liste aller Kandidaten (dann explizit vorgeben)
 *
 * Wurde die Klasse auch umbenannt (nicht nur das Package), hilft die explizite Vorgabe.
 */
public final class WorkerMainFinder {

    public static final String DEFAULT_MAIN = "de.prometheus.gdv.worker.WorkerMain";
    public static final String SIMPLE_NAME = "WorkerMain";

    private static final Logger LOG = Logger.getLogger(WorkerMainFinder.class.getName());

    private WorkerMainFinder() { }

    /** Sucht die Worker-Hauptklasse im Klassenpfad; wirft IOException, wenn nicht eindeutig findbar. */
    public static String find(List<Path> classpath) throws IOException {
        List<String> candidates = scan(classpath);
        if (candidates.isEmpty()) {
            throw new IOException("Keine " + SIMPLE_NAME + "-Klasse im Worker-Klassenpfad gefunden. "
                    + "Klassenpfad pruefen oder Hauptklasse explizit vorgeben "
                    + "(--worker-main bzw. -Dgdv.worker.main=<fqcn>).");
        }
        if (candidates.size() == 1) {
            LOG.info(() -> "Worker-Hauptklasse gefunden: " + candidates.get(0));
            return candidates.get(0);
        }
        if (candidates.contains(DEFAULT_MAIN)) {
            LOG.info(() -> "Mehrere WorkerMain-Kandidaten, nehme Original: " + DEFAULT_MAIN);
            return DEFAULT_MAIN;
        }
        List<String> gdvOnly = new ArrayList<>();
        for (String c : candidates) {
            if (c.toLowerCase().contains("gdv")) gdvOnly.add(c);
        }
        if (gdvOnly.size() == 1) {
            LOG.info(() -> "Worker-Hauptklasse gefunden: " + gdvOnly.get(0));
            return gdvOnly.get(0);
        }
        throw new IOException("Mehrdeutige WorkerMain-Kandidaten: " + candidates
                + " - bitte explizit vorgeben (--worker-main bzw. -Dgdv.worker.main=<fqcn>).");
    }

    /** Liefert alle gefundenen WorkerMain-Kandidaten (vollqualifizierte Namen). */
    public static List<String> scan(List<Path> classpath) throws IOException {
        List<String> found = new ArrayList<>();
        for (Path entry : classpath) {
            Path p = entry.toAbsolutePath();
            if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.walk(p)) {
                    s.filter(f -> f.getFileName().toString().equals(SIMPLE_NAME + ".class"))
                            .forEach(f -> found.add(toClassName(p.relativize(f).toString())));
                }
            } else if (Files.isRegularFile(p) && p.toString().endsWith(".jar")) {
                try (ZipFile zip = new ZipFile(p.toFile())) {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        String name = entries.nextElement().getName();
                        if (name.endsWith("/" + SIMPLE_NAME + ".class") || name.equals(SIMPLE_NAME + ".class")) {
                            found.add(toClassName(name));
                        }
                    }
                }
            } else {
                LOG.warning(() -> "Worker-Klassenpfad-Eintrag existiert nicht: " + p);
            }
        }
        return found;
    }

    private static String toClassName(String resourcePath) {
        String n = resourcePath.replace('\\', '/');
        if (n.endsWith(".class")) n = n.substring(0, n.length() - ".class".length());
        return n.replace('/', '.');
    }
}
