# Spec — GDV XML↔SNN Brücke

## Ziel

Ein Java-21-Programm soll GDV-Nachrichten konvertieren können, obwohl der GDV-XMLKonverter
nicht mit Java 21 zusammenarbeitet.

## Anforderungen

- R1: Konvertierung Datei → Datei mit Parametern Eingabedatei, Ausgabedatei, Release (Jahr).
- R2: Konvertierung Stream → Stream mit denselben Parametern (Release, Richtung).
- R3: Richtung wird aus der Endung abgeleitet (`.xml`→`.snn`, `.snn`→`.xml`).
- R4: Fehler des Konverters werden als Exception mit Exit-Code und Logauszug gemeldet.
- R5: GDVTools-Pfad und Java-8-Binary sind konfigurierbar (Argument/ENV).

## Akzeptanzkriterien

- Die Beispielnachricht `beispiel.xml` (Release 2000) wird zu einer nicht-leeren `.snn`.
- Der Rückweg `.snn` → `.xml` liefert wieder XML mit `<GDV`-Wurzel.
- Ohne Java 8 oder ohne GDVTools bricht der Aufruf mit klarer Meldung ab.

## Nicht-Ziele (noch)

- Persistent laufender Worker (Performance), GUI, Batch-Verzeichnisse.
