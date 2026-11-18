package de.prometheus.gdv;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * Haelt einen persistenten Java-8-Worker und spricht das binaere Protokoll:
 * CONVERT <release> <XML|SNN> <len>\n<bytes>  ->  OK <len>\n<bytes> | ERR <text>
 */
public final class GdvWorker implements Closeable {

    private final Process process;
    private final DataInputStream in;
    private final OutputStream out;

    private GdvWorker(Process process) {
        this.process = process;
        this.in = new DataInputStream(new BufferedInputStream(process.getInputStream()));
        this.out = process.getOutputStream();
    }

    public static GdvWorker start(Path java8, List<Path> classpath, Path props) throws IOException {
        String cp = classpath.stream().map(Path::toString).reduce((a, b) -> a + File.pathSeparator + b)
                .orElseThrow(() -> new IllegalArgumentException("Leerer Worker-Classpath"));
        ProcessBuilder pb = new ProcessBuilder(
                java8.toString(),
                "-Dgdv.props=" + props.toAbsolutePath(),
                "-cp", cp,
                "de.prometheus.gdv.worker.WorkerMain");
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
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
            w.close();
            throw e;
        }
        return w;
    }

    public byte[] convert(byte[] data, String release, Direction direction) throws IOException {
        String dir = direction == Direction.XML_TO_SNN ? "XML" : "SNN";
        synchronized (this) {
            out.write(("CONVERT " + release + " " + dir + " " + data.length + "\n").getBytes(StandardCharsets.UTF_8));
            out.write(data);
            out.flush();
            String header = readLineOrFail();
            if (header.startsWith("ERR")) {
                throw new IOException("Worker-Fehler: " + header.substring(3).trim());
            }
            if (!header.startsWith("OK ")) {
                throw new IOException("Unerwartete Antwort: " + header);
            }
            int len = Integer.parseInt(header.substring(3).trim());
            byte[] result = new byte[len];
            in.readFully(result);
            return result;
        }
    }

    private String readLineOrFail() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return new String(buf.toByteArray(), StandardCharsets.UTF_8);
            if (c != '\r') buf.write(c);
        }
        throw new IOException("Worker hat die Verbindung geschlossen");
    }

    @Override
    public void close() {
        try {
            out.write("QUIT\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) { }
        try {
            if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
