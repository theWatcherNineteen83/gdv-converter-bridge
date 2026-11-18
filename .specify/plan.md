# Plan

## Architektur

```
Java 21 Anwendung
   │  GdvBridge (convertFile / convertStream)
   ▼
Prozessstart: <java8> -cp "lib/*:GDV-Schadennetz.jar" sn.SN_XMLKonverter <in> 5 <release> <props>
   │
   ▼
GDVTools (Java 7/8)  →  Ausgabedatei + erg.txt (stdout)
```

## Entscheidungen

- **Prozess statt In-Process:** `sn.SN_XMLKonverter.main` schreibt auf stdout und beendet sich;
  ein eigener Prozess isoliert Logging, Exit und Classpath-Konflikte (Xerces/Xalan).
- **Streams über Temp-Dateien (Stufe 2a):** Der Konverter erwartet Dateinamen mit Endung;
  `convertStream` schreibt in ein Temp-Verzeichnis und liest das Ergebnis zurück.
- **Stufe 2b (offen):** persistenter Java-8-Worker mit Längenpräfix-Protokoll über stdin/stdout,
  falls der JVM-Start (~1–2 s) stört.
- **Eigene Properties-Datei** mit absoluten Pfaden, damit der Aufruf nicht vom Arbeitsverzeichnis abhängt.

## Risiken

- GDV-API `gdv.XmlGdvKonverter.convertToGdv(InputStream, OutputStream)` existiert, braucht aber
  eine Initialisierung der Satzarten (NPE in `SatzartElemente.getTnl` beim direkten Aufruf) →
  deshalb zunächst der CLI-Weg.
- Release muss zur Nachricht passen (Namespace `release2000` → `2000`).
