package de.prometheus.gdv.worker;

import gdv.XmlGdvKonverter;
import gdv.nachrichten.SatzartElemente;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Persistenter Java-8-Worker fuer echte Stream-Konvertierung (kein Temp-File).
 *
 * Protokoll (binaersicher, stdout wird exklusiv fuer das Protokoll genutzt):
 *   PING                                   -> PONG
 *   CONVERT <release> <XML|SNN> <len>\n<len Bytes>
 *                                          -> OK <len>\n<len Bytes> | ERR <text>
 *   QUIT                                   -> BYE
 */
public final class WorkerMain {

    private static final Map<String, XmlGdvKonverter> CACHE = new HashMap<String, XmlGdvKonverter>();
    private static String props;
    private static PrintStream proto;
    private static SatzartElemente satzarten;

    public static void main(String[] args) throws Exception {
        props = System.getProperty("gdv.props");
        if (props == null) {
            System.err.println("gdv.props fehlt");
            System.exit(2);
        }
        proto = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        // GDV-Code schreibt Trace auf System.out - das wuerde das Protokoll zerstoeren.
        System.setOut(new PrintStream(new OutputStream() {
            @Override public void write(int b) { }
        }));
        installExitBlocker();
        satzarten = new SatzartElemente();   // initialisiert die Satzart-Registry (sonst NPE)

        DataInputStream in = new DataInputStream(new BufferedInputStream(System.in));
        while (true) {
            String line = readLine(in);
            if (line == null) break;
            line = line.trim();
            if (line.length() == 0) continue;
            if (line.equals("QUIT")) { proto.println("BYE"); break; }
            if (line.equals("PING")) { proto.println("PONG"); continue; }
            if (!line.startsWith("CONVERT ")) { proto.println("ERR unbekannter Befehl"); continue; }

            String[] p = line.split("\\s+");
            if (p.length != 4) { proto.println("ERR Argumente erwartet: CONVERT <release> <XML|SNN> <len>"); continue; }
            try {
                String release = p[1];
                String direction = p[2].toUpperCase();
                int len = Integer.parseInt(p[3]);
                byte[] data = new byte[len];
                in.readFully(data);
                byte[] result = convert(data, release, direction);
                if (result.length == 0) {
                    proto.println("ERR leere Ausgabe");
                } else {
                    proto.println("OK " + result.length);
                    proto.write(result);
                    proto.flush();
                }
            } catch (Throwable t) {
                proto.println("ERR " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()).replace('\n', ' '));
            }
        }
    }

    private static byte[] convert(byte[] data, String release, String direction) throws Exception {
        XmlGdvKonverter converter = CACHE.get(release);
        if (converter == null) {
            converter = new XmlGdvKonverter("gdv" + release + ".xsd", props, release);
            CACHE.put(release, converter);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayInputStream in = new ByteArrayInputStream(data);
        if ("XML".equals(direction)) {
            converter.convertToGdv(in, out);
        } else {
            converter.convertToXml(in, out, release);
        }
        return out.toByteArray();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return new String(buf.toByteArray(), StandardCharsets.UTF_8);
            if (c != '\r') buf.write(c);
        }
        return buf.size() == 0 ? null : new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("removal")
    private static void installExitBlocker() {
        try {
            System.setSecurityManager(new SecurityManager() {
                @Override public void checkExit(int status) { throw new SecurityException("System.exit(" + status + ") blocked"); }
                @Override public void checkPermission(java.security.Permission perm) { }
            });
        } catch (Throwable ignored) { }
    }
}
