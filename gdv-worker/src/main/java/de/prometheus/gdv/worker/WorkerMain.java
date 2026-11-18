package de.prometheus.gdv.worker;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Persistenter Java-8-Worker: liest Zeilenbefehle von stdin, ruft den GDV-Konverter im selben
 * Prozess auf und antwortet auf stdout.
 *
 * Protokoll:
 *   CONVERT <release> <inDatei> <outDatei>  ->  OK <bytes> | ERR <text>
 *   PING                                    ->  PONG
 *   QUIT                                    ->  BYE
 *
 * Es wird eine SecurityManager-Instanz gesetzt, die System.exit blockiert - der GDV-Code ruft
 * es im Fehlerfall auf und wuerde sonst den Worker beenden.
 */
public final class WorkerMain {

    public static void main(String[] args) throws Exception {
        installExitBlocker();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        PrintStream realOut = System.out;
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.equals("QUIT")) { out.println("BYE"); break; }
            if (line.equals("PING")) { out.println("PONG"); continue; }
            if (!line.startsWith("CONVERT ")) { out.println("ERR unbekannter Befehl"); continue; }
            String[] p = line.split("\\s+", 4);
            if (p.length < 4) { out.println("ERR Argumente fehlen"); continue; }
            String release = p[1];
            String inFile = p[2];
            String outFile = p[3];
            try {
                String[] convArgs = new String[] { inFile, "5", release, System.getProperty("gdv.props") };
                // GDV-CLI im selben Prozess: Ausgabe (Log) wird umgeleitet, damit das Protokoll sauber bleibt
                System.setOut(devNull());
                sn.SN_XMLKonverter.main(convArgs);
                System.setOut(realOut);
                File f = new File(outFile);
                if (!f.exists()) {
                    out.println("ERR keine Ausgabedatei erzeugt");
                } else if (f.length() == 0) {
                    out.println("ERR Ausgabedatei ist leer");
                } else {
                    out.println("OK " + f.length());
                }
            } catch (Throwable t) {
                System.setOut(realOut);
                out.println("ERR " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }

    private static PrintStream devNull() {
        try {
            return new PrintStream(new OutputStream() {
                @Override public void write(int b) { }
            });
        } catch (Exception e) {
            return System.out;
        }
    }

    @SuppressWarnings("removal")
    private static void installExitBlocker() {
        try {
            System.setSecurityManager(new SecurityManager() {
                @Override public void checkExit(int status) {
                    throw new SecurityException("System.exit(" + status + ") blocked");
                }
                @Override public void checkPermission(java.security.Permission perm) { }
            });
        } catch (Throwable ignored) {
            // SecurityManager nicht verfuegbar: Worker bleibt trotzdem nutzbar, nur ohne Schutz
        }
    }
}
