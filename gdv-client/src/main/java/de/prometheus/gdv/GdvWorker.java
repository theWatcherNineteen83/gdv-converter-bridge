package de.prometheus.gdv;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Haelt einen persistenten Java-8-Worker und spricht das binaere Protokoll:
 * CONVERT <release> <XML|SNN> <len>\n<bytes>  ->  OK <len>\n<bytes> | ERR <text>
 *
 * Die Hauptklasse wird dynamisch im mitgegebenen Klassenpfad gesucht (WorkerMainFinder),
 * damit die Worker-Klassen in andere Projekte/Packages verschoben werden koennen.
 * Explizite Vorgabe: Parameter mainClass oder System-Property gdv.worker.main.
 *
 * stderr des Workers wird fortlaufend gelesen, per java.util.logging protokolliert und
 * die letzten Zeilen fuer Fehlermeldungen vorgehalten (frueher: verworfen).
 */
public final class GdvWorker implements Closeable {

    private static final Logger LOG = Logger.getLogger(GdvWorker.class.getName());
    private static final int STDERR_TAIL_LINES = 60;

    private final Process process;
    private final DataInputStream in;
    private final OutputStream out;
    private final Deque<String> stderrTail = new ArrayDeque<>();

    private GdvWorker(Process process) {
        this.process = process;
        this.in = new DataInputStream(new BufferedInputStream(process.getInputStream()));
        this.out = process.getOutputStream();
        Thread drainer = new Thread(this::drainStderr, "gdv-worker-stderr");
        drainer.setDaemon(true);
        drainer.start();
    }

    public static GdvWorker start(Path java8, List<Path> classpath, Path props) throws IOException {
        return start(java8, classpath, props, null);
    }

    public static GdvWorker start(Path java8, List<Path> classpath, Path props, String mainClass)
            throws IOException {
        return start(java8, classpath, props, mainClass, null);
    }

    /**
     * @param mainClass vollqualifizierter Name der Worker-Hauptklasse; null/leer =
     *                  System-Property gdv.worker.main, sonst dynamische Suche im Klassenpfad
     * @param workDir   Arbeitsverzeichnis des Workers (empfohlen: GDVTools-Verzeichnis, wie
     *                  beim CLI-Weg - GDV loest manche Pfade gegen das cwd auf); null = unveraendert
     */
    public static GdvWorker start(Path java8, List<Path> classpath, Path props, String mainClass,
                                  Path workDir) throws IOException {
        String main = mainClass == null || mainClass.isBlank() ? System.getProperty("gdv.worker.main") : mainClass;
        if (main == null || main.isBlank()) {
            main = WorkerMainFinder.find(classpath);
        }
        final String mainResolved = main;
        // Klassenpfad-Eintraege absolut machen: das Arbeitsverzeichnis des Workers kann
        // abweichen (z. B. GDVTools), relative Eintraege wuerden dort nicht mehr gefunden.
        String cp = classpath.stream().map(p -> p.toAbsolutePath().toString())
                .reduce((a, b) -> a + File.pathSeparator + b)
                .orElseThrow(() -> new IllegalArgumentException("Leerer Worker-Classpath"));
        List<String> cmd = List.of(
                java8.toString(),
                "-Dgdv.props=" + props.toAbsolutePath(),
                "-cp", cp,
                main);
        LOG.info(() -> "Starte Worker: " + java8 + " " + mainResolved + " (Klassenpfad-Eintraege: " + classpath.size() + ")");
        LOG.fine(() -> "Worker-Kommando: " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (workDir != null) pb.directory(workDir.toFile());
        pb.redirectError(ProcessBuilder.Redirect.PIPE);
        Process p = pb.start();
        GdvWorker w = new GdvWorker(p);
        try {
            w.out.write("PING\n".getBytes(StandardCharsets.UTF_8));
            w.out.flush();
            String pong = w.readLineOrFail();
            if (!"PONG".equals(pong)) {
                w.close();
                throw new IOException("Worker antwortet nicht auf PING: " + pong);
            }
        } catch (IOException e) {
            String tail = w.stderrTail();
            w.close();
            IOException ex = new IOException("Worker-Start fehlgeschlagen (" + mainResolved + "): " + e.getMessage()
                    + (tail.isEmpty() ? "" : "\nWorker-stderr:\n" + tail), e);
            LOG.log(Level.SEVERE, ex.getMessage(), ex);
            throw ex;
        }
        return w;
    }

    public byte[] convert(byte[] data, String release, Direction direction) throws IOException {
        String dir = direction == Direction.XML_TO_SNN ? "XML" : "SNN";
        synchronized (this) {
            try {
                out.write(("CONVERT " + release + " " + dir + " " + data.length + "\n").getBytes(StandardCharsets.UTF_8));
                out.write(data);
                out.flush();
                String header = readLineOrFail();
                if (header.startsWith("ERR")) {
                    throw new IOException("Worker-Fehler: " + header.substring(3).trim() + stderrSuffix());
                }
                if (!header.startsWith("OK ")) {
                    throw new IOException("Unerwartete Antwort: " + header + stderrSuffix());
                }
                int len = Integer.parseInt(header.substring(3).trim());
                byte[] result = new byte[len];
                in.readFully(result);
                return result;
            } catch (IOException e) {
                LOG.log(Level.SEVERE, "Worker-Konvertierung fehlgeschlagen (Release " + release
                        + ", Richtung " + dir + ", " + data.length + " Bytes Eingabe)", e);
                throw e;
            }
        }
    }

    private String readLineOrFail() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return new String(buf.toByteArray(), StandardCharsets.UTF_8);
            if (c != '\r') buf.write(c);
        }
        throw new IOException("Worker hat die Verbindung geschlossen" + stderrSuffix());
    }

    private void drainStderr() {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                final String l = line;
                synchronized (stderrTail) {
                    stderrTail.addLast(line);
                    while (stderrTail.size() > STDERR_TAIL_LINES) stderrTail.removeFirst();
                }
                LOG.warning(() -> "[worker-stderr] " + l);
            }
        } catch (IOException ignored) {
            // Prozess beendet / Stream geschlossen
        }
    }

    private String stderrTail() {
        synchronized (stderrTail) {
            return String.join("\n", stderrTail);
        }
    }

    private String stderrSuffix() {
        String tail = stderrTail();
        return tail.isEmpty() ? "" : "\nWorker-stderr:\n" + tail;
    }

    @Override
    public void close() {
        try {
            out.write("QUIT\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) { }
        try {
            if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                LOG.warning("Worker beendet sich nicht auf QUIT - erzwinge Abbruch");
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
