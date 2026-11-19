package de.prometheus.gdv;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Mini-Test-GUI fuer die GDV-Bridge (Swing, keine externen Abhaengigkeiten).
 *
 * Start:  java -cp gdv-client-0.2.1.jar de.prometheus.gdv.TestGui
 *         oder: java -jar gdv-client-0.2.1.jar --gui   (bzw. Main --gui)
 *
 * Alle Pfade (GDVTools, Java 8, Property-Datei, Eingabe, Ausgabe, Worker-Klassenpfad,
 * Worker-Hauptklasse) sind eintragbar; die Wege native / worker / CLI lassen sich einzeln
 * oder gemeinsam testen. Ergebnis je Weg: Bytes, SHA-256, Dauer; anschliessender
 * Byte-Vergleich aller erfolgreichen Wege. Einstellungen werden in
 * ~/.gdv-bridge-gui.properties gespeichert.
 *
 * Headless (Server/CI): --selftest mit denselben Pfaden, Ausgabe auf stdout, Exit 0 nur
 * wenn alle gewaehlten Wege erfolgreich und byte-identisch sind.
 */
public final class TestGui {

    private static final Logger LOG = Logger.getLogger(TestGui.class.getName());
    private static final Path SETTINGS = Path.of(System.getProperty("user.home"), ".gdv-bridge-gui.properties");

    // ---------------- Kernlogik (Swing-frei, auch headless nutzbar) ----------------

    /** Alle einstellbaren Pfade und Optionen. */
    public static final class Config {
        public Path gdvHome;
        public Path java8;          // null = automatische Aufloesung (Java8Locator)
        public Path propertyFile;   // null = generierte Property-Datei
        public List<Path> workerClasspath = List.of();
        public String workerMain;   // null/leer = dynamische Suche (WorkerMainFinder)
        public Path input;
        public Path outDir;
        public String release = "2003";
        public List<String> modes = List.of("native", "worker", "cli");
    }

    /** Ergebnis eines Weges. */
    public record ModeResult(String mode, boolean ok, long bytes, String sha256, long millis,
                             String detail, Path outFile) { }

    /**
     * Fuehrt die gewaehlten Wege nacheinander aus und schreibt je Weg eine Ausgabedatei
     * <basis>-<modus>.<endung> in outDir. Wirft nicht bei Einzelfehlern; Fehler stehen
     * im jeweiligen ModeResult.
     */
    public static List<ModeResult> runAll(Config c, Consumer<String> log) throws IOException {
        if (c.gdvHome == null || !Files.isDirectory(c.gdvHome.resolve("konfig")))
            throw new IOException("GDVTools-Verzeichnis ungueltig (konfig/ fehlt): " + c.gdvHome);
        if (c.input == null || !Files.isRegularFile(c.input))
            throw new IOException("Eingabedatei fehlt: " + c.input);
        if (c.release == null || c.release.isBlank())
            throw new IOException("Release fehlt (z. B. 2003)");
        Direction dir = Direction.of(c.input.getFileName().toString());
        Path outDir = c.outDir != null ? c.outDir : c.input.toAbsolutePath().getParent();
        Files.createDirectories(outDir);
        byte[] data = Files.readAllBytes(c.input);
        log.accept("Eingabe: " + c.input + " (" + data.length + " Bytes), Richtung " + dir
                + ", Release " + c.release);
        String base = c.input.getFileName().toString().replaceFirst("\\.[^.]+$", "");

        List<ModeResult> results = new ArrayList<>();
        for (String mode : c.modes) {
            long t0 = System.nanoTime();
            Path out = outDir.resolve(base + "-" + mode + dir.outExt());
            try (GdvBridge bridge = openBridge(mode, c)) {
                byte[] result = bridge.convertStream(data, c.release, dir);
                if (result.length == 0) throw new IOException("Leeres Ergebnis (0 Bytes)");
                Files.write(out, result);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                String sha = sha256(result);
                log.accept(mode + ": OK " + result.length + " Bytes, SHA-256 " + sha + ", " + ms + " ms -> " + out);
                results.add(new ModeResult(mode, true, result.length, sha, ms, out.toString(), out));
            } catch (Exception e) {
                long ms = (System.nanoTime() - t0) / 1_000_000;
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                log.accept(mode + ": FEHLER nach " + ms + " ms: " + e + "\n" + sw);
                results.add(new ModeResult(mode, false, 0, "", ms, String.valueOf(e), null));
            }
        }
        log.accept(compare(results));
        return results;
    }

    private static GdvBridge openBridge(String mode, Config c) {
        Path java8 = c.java8;
        return switch (mode) {
            case "native" -> GdvBridge.nativeMode(c.gdvHome);
            case "worker" -> {
                if (c.workerClasspath.isEmpty())
                    throw new IllegalArgumentException("Worker-Klassenpfad fehlt (z. B. gdv-worker-0.2.1.jar)");
                String main = c.workerMain == null || c.workerMain.isBlank() ? null : c.workerMain.trim();
                yield new GdvBridge(c.gdvHome, java8, c.propertyFile, c.workerClasspath, main);
            }
            case "cli" -> new GdvBridge(c.gdvHome, java8, c.propertyFile, List.of());
            default -> throw new IllegalArgumentException("Unbekannter Modus: " + mode);
        };
    }

    /** Vergleichszeilen: byte-identische Gruppen und Abweichungen. */
    public static String compare(List<ModeResult> results) {
        StringBuilder sb = new StringBuilder("Vergleich: ");
        List<ModeResult> ok = results.stream().filter(ModeResult::ok).toList();
        if (ok.isEmpty()) return sb.append("kein erfolgreicher Weg.").toString();
        Map<String, List<String>> bySha = new LinkedHashMap<>();
        for (ModeResult r : ok) bySha.computeIfAbsent(r.sha256(), k -> new ArrayList<>()).add(r.mode());
        if (bySha.size() == 1) {
            sb.append("alle ").append(ok.size()).append(" Wege byte-identisch (SHA-256 ")
                    .append(ok.get(0).sha256()).append(").");
        } else {
            sb.append(bySha.size()).append(" unterschiedliche Ergebnisse: ");
            bySha.forEach((sha, modes) -> sb.append("[").append(String.join(", ", modes))
                    .append(" = ").append(sha, 0, Math.min(12, sha.length())).append("...] "));
            sb.append("(native kann beim Rueckweg ein Newline nach der XML-Deklaration abweichen - DOM-Vergleich pruefen).");
        }
        long failed = results.size() - ok.size();
        if (failed > 0) sb.append(" ").append(failed).append(" Weg(e) fehlgeschlagen.");
        return sb.toString();
    }

    static String sha256(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** JUL-Protokolle der Bridge in ein Ziel umleiten (GUI-Logflaeche bzw. stdout). */
    static Handler logHandler(Consumer<String> sink) {
        return new Handler() {
            @Override public void publish(LogRecord r) {
                String msg = "[" + r.getLevel() + "] " + r.getLoggerName()
                        .replaceFirst("^de\\.prometheus\\.gdv\\.?", "") + ": " + format(r);
                sink.accept(msg);
                if (r.getThrown() != null) {
                    StringWriter sw = new StringWriter();
                    r.getThrown().printStackTrace(new PrintWriter(sw));
                    sink.accept(sw.toString());
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
            private String format(LogRecord r) {
                String m = r.getMessage();
                if (m == null) return "";
                if (r.getParameters() != null) {
                    try { return java.text.MessageFormat.format(m, r.getParameters()); }
                    catch (Exception ignored) { }
                }
                return m;
            }
        };
    }

    // ---------------- Headless-Selftest ----------------

    private static int selftest(String[] args) {
        Map<String, String> a = Main.parse(args);
        Config c = new Config();
        try {
            c.gdvHome = Path.of(req(a, "gdv-home"));
            c.input = Path.of(req(a, "in"));
            c.release = a.getOrDefault("release", "2003");
            if (a.containsKey("out-dir")) c.outDir = Path.of(a.get("out-dir"));
            if (a.containsKey("java8")) c.java8 = Path.of(a.get("java8"));
            if (a.containsKey("props")) c.propertyFile = Path.of(a.get("props"));
            c.workerClasspath = Main.splitClasspath(a.get("worker-cp"));
            c.workerMain = a.get("worker-main");
            if (a.containsKey("modes")) c.modes = List.of(a.get("modes").split(","));
        } catch (Exception e) {
            System.err.println("Selftest-Parameter fehlerhaft: " + e.getMessage());
            return 2;
        }
        Logger bridgeLog = Logger.getLogger("de.prometheus.gdv");
        Handler h = logHandler(s -> System.out.println("LOG " + s));
        h.setLevel(Level.ALL);
        bridgeLog.addHandler(h);
        try {
            List<ModeResult> results = runAll(c, System.out::println);
            boolean allOk = results.stream().allMatch(ModeResult::ok);
            boolean identical = results.stream().filter(ModeResult::ok)
                    .map(ModeResult::sha256).distinct().count() <= 1;
            System.out.println(allOk && identical ? "SELFTEST OK" : "SELFTEST FEHLGESCHLAGEN");
            return allOk && identical ? 0 : 1;
        } catch (Exception e) {
            e.printStackTrace();
            return 1;
        } finally {
            bridgeLog.removeHandler(h);
        }
    }

    private static String req(Map<String, String> a, String key) {
        String v = a.get(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("--" + key + " fehlt");
        return v;
    }

    // ---------------- Swing-Oberflaeche ----------------

    private final JFrame frame = new JFrame("GDV-Bridge Test-GUI");
    private final JTextField gdvHomeField = new JTextField(36);
    private final JTextField java8Field = new JTextField(36);
    private final JTextField propsField = new JTextField(36);
    private final JTextField inputField = new JTextField(36);
    private final JTextField outDirField = new JTextField(36);
    private final JTextField workerCpField = new JTextField(36);
    private final JTextField workerMainField = new JTextField(24);
    private final JComboBox<String> releaseBox = new JComboBox<>(new String[]{"2003", "2000", "2007", "2011", "2015", "2019"});
    private final JLabel directionLabel = new JLabel("Richtung: -");
    private final JCheckBox nativeBox = new JCheckBox("native (Java 21, kein Java 8)", true);
    private final JCheckBox workerBox = new JCheckBox("worker (Java-8-Prozess)", true);
    private final JCheckBox cliBox = new JCheckBox("CLI-Fallback (Java 8)", true);
    private final JButton runButton = new JButton("Test starten");
    private final JButton findMainButton = new JButton("WorkerMain suchen");
    private final DefaultTableModel tableModel = new DefaultTableModel(
            new Object[]{"Weg", "Status", "Bytes", "SHA-256", "ms", "Datei / Fehler"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTextArea logArea = new JTextArea(14, 80);
    private final JLabel summaryLabel = new JLabel("Bereit.");

    private TestGui() {
        releaseBox.setEditable(true);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JPanel paths = new JPanel(new GridBagLayout());
        paths.setBorder(BorderFactory.createTitledBorder("Pfade"));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 6, 3, 6);
        g.anchor = GridBagConstraints.WEST;
        int row = 0;
        addPathRow(paths, g, row++, "GDVTools-Verzeichnis:", gdvHomeField, true);
        addPathRow(paths, g, row++, "Java 8 (Binary oder JRE-Verzeichnis, leer = Auto):", java8Field, true);
        addPathRow(paths, g, row++, "Property-Datei (leer = automatisch generiert):", propsField, false);
        addPathRow(paths, g, row++, "Eingabedatei (.xml oder .snn):", inputField, false);
        addPathRow(paths, g, row++, "Ausgabe-Verzeichnis (leer = neben Eingabe):", outDirField, true);
        addPathRow(paths, g, row++, "Worker-Klassenpfad (mehrere mit " + File.pathSeparator + "):", workerCpField, false);

        JPanel mainRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        mainRow.add(new JLabel("Worker-Hauptklasse (leer = dynamisch):"));
        mainRow.add(workerMainField);
        mainRow.add(findMainButton);
        g.gridx = 0; g.gridy = row; g.gridwidth = 2; g.fill = GridBagConstraints.HORIZONTAL;
        paths.add(mainRow, g);

        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        options.setBorder(BorderFactory.createTitledBorder("Test"));
        options.add(new JLabel("Release:"));
        options.add(releaseBox);
        options.add(directionLabel);
        options.add(nativeBox);
        options.add(workerBox);
        options.add(cliBox);
        options.add(runButton);

        JTable table = new JTable(tableModel);
        table.getColumnModel().getColumn(3).setPreferredWidth(260);
        table.getColumnModel().getColumn(5).setPreferredWidth(320);
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setPreferredSize(new Dimension(900, 110));
        JScrollPane logScroll = new JScrollPane(logArea);

        JPanel bottom = new JPanel(new BorderLayout(6, 6));
        bottom.add(summaryLabel, BorderLayout.NORTH);
        bottom.add(tableScroll, BorderLayout.CENTER);
        bottom.add(logScroll, BorderLayout.SOUTH);

        frame.setLayout(new BorderLayout(6, 6));
        frame.add(paths, BorderLayout.NORTH);
        frame.add(options, BorderLayout.CENTER);
        frame.add(bottom, BorderLayout.SOUTH);
        ((JComponent) frame.getContentPane()).setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { saveSettings(); frame.dispose(); }
        });

        inputField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updateDirection(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updateDirection(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateDirection(); }
        });
        findMainButton.addActionListener(e -> findWorkerMain());
        runButton.addActionListener(e -> runInBackground());

        loadSettings();
        updateDirection();
        frame.pack();
        frame.setLocationRelativeTo(null);
    }

    private void addPathRow(JPanel p, GridBagConstraints g, int row, String label, JTextField field, boolean dirOnly) {
        g.gridx = 0; g.gridy = row; g.gridwidth = 1; g.fill = GridBagConstraints.NONE; g.weightx = 0;
        p.add(new JLabel(label), g);
        JPanel fp = new JPanel(new BorderLayout(4, 0));
        fp.add(field, BorderLayout.CENTER);
        JButton browse = new JButton("...");
        browse.setMargin(new Insets(1, 6, 1, 6));
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(field.getText().isBlank() ? null : Path.of(field.getText()).toFile());
            fc.setFileSelectionMode(dirOnly && field != inputField && field != propsField
                    ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
            if (field == inputField) fc.setFileFilter(new FileNameExtensionFilter("GDV-Dateien (*.xml, *.snn)", "xml", "snn"));
            if (field == workerCpField) fc.setMultiSelectionEnabled(true);
            if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                if (field == workerCpField && fc.getSelectedFiles().length > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (java.io.File f : fc.getSelectedFiles()) {
                        if (sb.length() > 0) sb.append(File.pathSeparator);
                        sb.append(f.getAbsolutePath());
                    }
                    field.setText(sb.toString());
                } else {
                    field.setText(fc.getSelectedFile().getAbsolutePath());
                }
            }
        });
        fp.add(browse, BorderLayout.EAST);
        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1;
        p.add(fp, g);
    }

    private void updateDirection() {
        try {
            Direction d = Direction.of(Path.of(inputField.getText().trim()).getFileName().toString());
            directionLabel.setText("Richtung: " + (d == Direction.XML_TO_SNN ? "XML -> GDV (.snn)" : "GDV (.snn) -> XML"));
        } catch (Exception e) {
            directionLabel.setText("Richtung: - (Endung .xml/.snn)");
        }
    }

    private void findWorkerMain() {
        try {
            List<String> candidates = WorkerMainFinder.scan(Main.splitClasspath(workerCpField.getText()));
            if (candidates.isEmpty()) {
                log("WorkerMain-Suche: keine Klasse mit einfachem Namen WorkerMain im Klassenpfad gefunden.");
            } else {
                log("WorkerMain-Kandidaten: " + candidates);
                if (candidates.size() == 1) workerMainField.setText(candidates.get(0));
            }
        } catch (Exception e) {
            log("WorkerMain-Suche fehlgeschlagen: " + e);
        }
    }

    private Config configFromFields() {
        Config c = new Config();
        c.gdvHome = Path.of(gdvHomeField.getText().trim());
        c.java8 = java8Field.getText().isBlank() ? null : Path.of(java8Field.getText().trim());
        c.propertyFile = propsField.getText().isBlank() ? null : Path.of(propsField.getText().trim());
        c.input = Path.of(inputField.getText().trim());
        c.outDir = outDirField.getText().isBlank() ? null : Path.of(outDirField.getText().trim());
        c.workerClasspath = Main.splitClasspath(workerCpField.getText());
        c.workerMain = workerMainField.getText();
        c.release = String.valueOf(releaseBox.getSelectedItem()).trim();
        List<String> modes = new ArrayList<>();
        if (nativeBox.isSelected()) modes.add("native");
        if (workerBox.isSelected()) modes.add("worker");
        if (cliBox.isSelected()) modes.add("cli");
        c.modes = modes;
        return c;
    }

    private void runInBackground() {
        Config c;
        try {
            c = configFromFields();
            if (c.modes.isEmpty()) throw new IllegalArgumentException("Mindestens einen Weg auswaehlen.");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, e.getMessage(), "Eingabe fehlerhaft", JOptionPane.ERROR_MESSAGE);
            return;
        }
        saveSettings();
        tableModel.setRowCount(0);
        logArea.setText("");
        runButton.setEnabled(false);
        summaryLabel.setText("Laeuft: " + String.join(", ", c.modes) + " ...");
        Logger bridgeLog = Logger.getLogger("de.prometheus.gdv");
        Handler h = logHandler(this::log);
        h.setLevel(Level.ALL);
        bridgeLog.addHandler(h);
        new SwingWorker<List<ModeResult>, Void>() {
            @Override protected List<ModeResult> doInBackground() throws Exception {
                return runAll(c, TestGui.this::log);
            }
            @Override protected void done() {
                bridgeLog.removeHandler(h);
                runButton.setEnabled(true);
                try {
                    List<ModeResult> results = get();
                    for (ModeResult r : results) {
                        tableModel.addRow(new Object[]{r.mode(), r.ok() ? "OK" : "FEHLER",
                                r.ok() ? r.bytes() : "-", r.ok() ? r.sha256() : "-", r.millis(), r.detail()});
                    }
                    summaryLabel.setText(compare(results));
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    summaryLabel.setText("Fehler: " + cause.getMessage());
                    log("Abbruch: " + cause);
                }
            }
        }.execute();
    }

    private void log(String s) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(s + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void loadSettings() {
        if (!Files.exists(SETTINGS)) return;
        Properties p = new Properties();
        try (var in = Files.newInputStream(SETTINGS)) { p.load(in); } catch (IOException e) { return; }
        gdvHomeField.setText(p.getProperty("gdvHome", ""));
        java8Field.setText(p.getProperty("java8", ""));
        propsField.setText(p.getProperty("props", ""));
        inputField.setText(p.getProperty("input", ""));
        outDirField.setText(p.getProperty("outDir", ""));
        workerCpField.setText(p.getProperty("workerCp", ""));
        workerMainField.setText(p.getProperty("workerMain", ""));
        releaseBox.setSelectedItem(p.getProperty("release", "2003"));
        nativeBox.setSelected(Boolean.parseBoolean(p.getProperty("native", "true")));
        workerBox.setSelected(Boolean.parseBoolean(p.getProperty("worker", "true")));
        cliBox.setSelected(Boolean.parseBoolean(p.getProperty("cli", "true")));
    }

    private void saveSettings() {
        Properties p = new Properties();
        p.setProperty("gdvHome", gdvHomeField.getText());
        p.setProperty("java8", java8Field.getText());
        p.setProperty("props", propsField.getText());
        p.setProperty("input", inputField.getText());
        p.setProperty("outDir", outDirField.getText());
        p.setProperty("workerCp", workerCpField.getText());
        p.setProperty("workerMain", workerMainField.getText());
        p.setProperty("release", String.valueOf(releaseBox.getSelectedItem()));
        p.setProperty("native", String.valueOf(nativeBox.isSelected()));
        p.setProperty("worker", String.valueOf(workerBox.isSelected()));
        p.setProperty("cli", String.valueOf(cliBox.isSelected()));
        try (var out = Files.newOutputStream(SETTINGS)) { p.store(out, "GDV-Bridge Test-GUI"); }
        catch (IOException e) { LOG.warning("Einstellungen nicht speicherbar: " + e); }
    }

    // ---------------- Einstieg ----------------

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--selftest")) {
            System.exit(selftest(java.util.Arrays.copyOfRange(args, 1, args.length)));
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("Keine grafische Umgebung. Headless-Test: java -cp gdv-client.jar "
                    + TestGui.class.getName() + " --selftest --gdv-home <GDVTools> --in <datei> --release 2003");
            System.exit(2);
        }
        boolean autorun = java.util.Arrays.asList(args).contains("--autorun");
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) { }
            TestGui gui = new TestGui();
            gui.frame.setVisible(true);
            if (autorun) gui.runInBackground();
        });
    }
}
