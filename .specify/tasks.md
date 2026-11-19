# Tasks

| # | Aufgabe | Status |
|---|---------|--------|
| 1 | GDVTools analysieren (Bytecode, Einstiegspunkte, Libs) | erledigt |
| 2 | Java-8-Laufzeit bereitstellen, Basiskonvertierung reproduzieren | erledigt |
| 3 | Verhalten unter Java 21 pruefen (Release 2000 + 2003) | erledigt, siehe Befund |
| 4 | Client: Datei-Modus (R1) | erledigt |
| 5 | Client: Stream-Modus ueber Temp-Dateien (R2) | erledigt |
| 6 | Tests mit beispiel.xml und beispiel2003.xml (beide Richtungen) | erledigt |
| 7 | Persistenter Worker (Java 8, Zeilenprotokoll) | erledigt |
| 8 | Build/Sourcen auf Build-Host, privates Repo | erledigt |
| 9 | Streams ohne Temp-Dateien im Worker (Protokoll mit Laengenprefix) | erledigt |

## Stream-Betrieb (Task 9)

- Persistenter Java-8-Worker, binaeres Protokoll:
  `CONVERT <release> <XML|SNN> <len>\n<bytes>` -> `OK <len>\n<bytes>` oder `ERR <text>`
- Die Konvertierung laeuft in-memory ueber `gdv.XmlGdvKonverter`. Voraussetzung ist
  `new SatzartElemente()` als Initialisierung - ohne sie wirft der Konverter eine NPE in
  `SatzartElemente.getTnl`.
- Verifiziert: Release 2003, 8,8 MB -> 6.062.587 Bytes, SHA-256 identisch zum CLI-Weg
  (ce4f3f11...1751). Ohne Worker-Classpath faellt der Client auf Temp-Dateien zurueck.

## Befund Java 21 (18.11.2026, seziert)

- Der Konvertierungskern laeuft unter Java 21 korrekt. Entscheidend ist der Zielstream:
  - direkter `FileOutputStream`: **0 Bytes**, Exit 0, keine Exception (reproduziert)
  - `ByteArrayOutputStream` (in-memory): **6.062.587 Bytes**, SHA-256 identisch zu Java 8
- Java 8 liefert in beiden Faellen dasselbe Ergebnis. Die Abweichung liegt also im
  abschliessenden Schreib-/Flush-Pfad des GDV-Serializers gegen einen Dateistream, nicht in
  der Konvertierungslogik. Der GDV-Code schluckt den Fehler (keine Exception sichtbar).
- Konsequenz: native Modus konvertiert in den Speicher und schreibt die Datei selbst.
- Verifiziert: Release 2003 -> 6.062.587 Bytes, Release 2000 -> 37.214 Bytes, beide
  byte-identisch zum Java-8-Weg.

## Befund Java 21 (erste Beobachtung)

- Release 2000, beispiel.xml (47 KB): Java 21 erzeugt 37.214 Bytes, sieht korrekt aus.
- Release 2003, beispiel2003.xml (8,8 MB): Java 21 beendet mit exit 0, schreibt 954
  Log-Zeilen wie Java 8, erzeugt aber eine **0-Byte-Datei**. Java 8 erzeugt 6.062.587 Bytes.
- Damit ist die Inkompatibilitaet real, aber **still**: kein Fehler, kein Exit-Code, nur leere
  Ausgabe. Deshalb prueft die Bruecke die Ausgabegroesse und bricht bei 0 Bytes ab.

## v0.2.0 (19.11.2026): Windows, dynamischer Worker, Logging, Rueckweg-Fix

Anlass: Mail von Georg Seher (19.11.2026, 14:07) - Integration in Windows/Spring Boot,
Klassen in andere Projekte verschoben.

- **Windows:** Klassenpfade ohne Wildcards - `lib/*.jar` wird enumeriert (`GdvBridge.libJars`),
  Trennzeichen `File.pathSeparator`, Java-8-Binary-Aufloesung inkl. `java.exe` (`Java8Locator`).
- **Dynamische Worker-Hauptklasse:** `WorkerMainFinder` sucht `WorkerMain.class` in
  Verzeichnissen/Jars des extern mitgegebenen Klassenpfads; `--worker-cp` akzeptiert mehrere
  Eintraege (`:`/`;`), `--worker-main` bzw. `-Dgdv.worker.main` als explizite Vorgabe.
  Worker-Klassenpfad wird absolut normalisiert, Worker-cwd = GDVTools (wie CLI).
- **Logging:** java.util.logging; Fehler mit Stacktrace (SEVERE), Worker-stderr wird
  mitgelesen (WARNING, Prefix `[worker-stderr]`) und die letzten 60 Zeilen in Fehlermeldungen
  eingehaengt; Worker schreibt Stacktraces auf stderr.
- **Rueckweg-Fix (SNN->XML):** Der 3. Parameter von `gdv.XmlGdvKonverter.convertToXml` ist
  die **Ausgabe-Codierung**, nicht das Release (Bytecode-Befund: `toXml(out, arg3)` ->
  `LSOutput.setEncoding(arg3)`). Uebergabe des ReleaseStrings erzeugte
  `LSException: unsupported-encoding`. Fix: `null` (Vendor-Default, wie GDV-CLI).
- **Verifiziert (miniedi, Zulu 21 + Temurin 8, GDVTools Rev. 324):**
  - Hinweg K2.xml/2003: native, worker (Original + verschobenes Package `com.huk.gdv.worker`,
    dynamisch gefunden + explizit), CLI: alle **6.062.587 Bytes, SHA-256
    ce4f3f117cecf923edad5f98fa07c60f82674c6c796ab45c7abf68c1a5321751** (Referenz identisch).
  - Rueckweg RITA_2/2000: worker + CLI **byte-identisch** (47.262 Bytes,
    SHA-256 dc43bf88e4bee41bafc6909a93b5a41decb7a586d0e5a4753d8fb0e01e7c610b);
    native: DOM-identisch, 47.261 Bytes - einziges Delta ist ein Newline nach der
    XML-Deklaration (JDK-Serializer vs. Xerces).
  - Tests: 13/13 gruen (inkl. 2 Integrationstests mit GDVTools/JDK8).
  - Fehler-Logging: kaputtes XML -> SAXParseException-Stacktrace im Log; unbekanntes
    Release -> Worker-ERR + FileNotFoundException-Stacktrace via `[worker-stderr]`.
- **Offen (Vendor):** Rueckweg K2/Release 2003 wirft auch unter Java 8 (CLI) eine NPE in
  `DatensatzContainerListe2003.createContainerListFromGdv:224` (`satzartName` null) -
  Daten-/Release-spezifischer Vendor-Fehler, nicht die Bridge. Rueckweg Release 2000 ok.
- **Windows-Laufzeit nicht getestet** (kein Windows-Host verfuegbar); Pfadlogik ist
  unit-getestet und nutzt nur platformneutrale APIs.

## v0.2.1 (19.11.2026): Test-GUI, Log-Datei-Option, keine stillen Swallows

- **TestGui** (Swing, ohne externe Abhaengigkeiten): alle Pfade eintragbar, Modi
  native/worker/CLI einzeln oder gemeinsam testbar, Byte-/SHA-256-Vergleich, live
  JUL-Log-Anzeige inkl. Worker-stderr; `--gui` via CLI-Starter, `--selftest` fuer
  headless (Exit 0 nur bei Erfolg + Byte-Identitaet). Einstellungen persistiert in
  `~/.gdv-bridge-gui.properties`.
- **Log-Datei-Option:** `--log-file <pfad>` (Main), alternativ `-Dgdv.log.file` bzw.
  `$GDV_LOG_FILE` (Spring-Boot-tauglich). JUL FileHandler (append, SimpleFormatter)
  am Root-Logger; Konsolen-Logging bleibt. Verifiziert: Info- und SEVERE-Stacktraces
  landen in der Datei (Smoke-Test native RITA_2/2000: 37.214 Bytes, exit 0;
  kaputtes XML: exit 1, 4x SEVERE mit Stacktrace im Log).
- **Stille Exception-Schlucker beseitigt** (NativeGdv): JAR-Ladefehler, ClassLoader-
  close-Fehler werden jetzt geloggt (WARNING/FINE) statt `ignored`.
- Build miniedi (Zulu 25): `mvn -B clean package -Dgdv.home=/home/prometheus/gdv/GDVTools`
  BUILD SUCCESS, 19 Tests, 0 Fehler (2 Integrationstests geskipped: kein JDK 8 auf Host).

## v0.2.2 (Mail 140, Windows-Feldtest Georg Seher)

- Befund: Worker startet, GDV-Klassen laden, aber Schema-Load scheitert:
  `FileNotFoundException: GDV Converter BridgegdvconvertGDVToolskonfigxml\gdv2003.xsd`.
  Ursache: generierte Property-Datei schrieb Windows-Pfade mit einfachen Backslashes;
  der Vendor-Parser (Properties-Style-Escapes) frisst sie. Beweis: mitgelieferte
  `konfig/konverter.property` nutzt doppelte Backslashes (`schemaPath=konfig\\xml\\`).
- Fix: `GdvBridge.propsDir()/escapePropsPath()` verdoppeln Backslashes in generierten
  Properties (Worker/CLI und NativeGdv). Linux-Pfade unveraendert.
- Zusaetzlich: `GdvBridge.mainJar()` toleriert versionierte GDV-Schadennetz*.jar
  (GDVTools-Verzeichnis oder lib/, exakter Name hat Vorrang) - Georgs Hinweis
  "die jar Dateien haben Versionen".
- Tests: 25/25 gruen (neu WindowsPropsTest 8: Reproduktion des Fress-Mechanismus,
  Escape-Rundlauf, propsDir, mainJar-Varianten). Selftest miniedi: Hinweg K2/2003
  native+worker+cli byte-identisch ce4f3f11...1751; Rueckweg RITA_2/2000 worker+cli
  byte-identisch dc43bf88...610b. Windows-Laufzeit weiter ungetestet (kein Host).
