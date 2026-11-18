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

## Befund Java 21 (18.11.2026)

- Release 2000, beispiel.xml (47 KB): Java 21 erzeugt 37.214 Bytes, sieht korrekt aus.
- Release 2003, beispiel2003.xml (8,8 MB): Java 21 beendet mit exit 0, schreibt 954
  Log-Zeilen wie Java 8, erzeugt aber eine **0-Byte-Datei**. Java 8 erzeugt 6.062.587 Bytes.
- Damit ist die Inkompatibilitaet real, aber **still**: kein Fehler, kein Exit-Code, nur leere
  Ausgabe. Deshalb prueft die Bruecke die Ausgabegroesse und bricht bei 0 Bytes ab.
