# GDV Converter Bridge

Ruft den GDV-XMLKonverter (GDVTools, `sn.SN_XMLKonverter`) aus einem Java-21-Programm auf.
Der Konverter selbst laeuft in einem **Java-8-Prozess**, weil die GDV-Jars gegen eine aeltere
JDK-Generation gebaut sind (Bytecode 51 = Java 7).

## Status

Stufe 1 (Dateien) und Stufe 2 (Streams) sind umgesetzt. Streams laufen ueber einen persistenten
Java-8-Worker ohne temporaere Dateien; ohne Worker-Classpath gibt es einen Temp-Datei-Fallback.
Siehe `.specify/spec.md`, `.specify/plan.md`, `.specify/tasks.md`.

## Voraussetzungen

- JDK 21 (Client), JDK 8 (Konverter-Prozess), z. B. `/opt/jdk8`
- GDVTools entpackt, z. B. `/opt/gdv/GDVTools`

## Aufruf (Datei)

```bash
java -cp gdv-client/target/classes de.prometheus.gdv.Main \
  --gdv-home /opt/gdv/GDVTools \
  --java8    /opt/jdk8/bin/java \
  --in       beispiel.xml \
  --out      beispiel.snn \
  --release  2000
```

Richtung ergibt sich aus der Endung der Eingabedatei (`.xml` -> `.snn`, `.snn` -> `.xml`).

## Native Modus (Java 21, ohne Java 8)

Der Konvertierungskern laeuft auch unter Java 21 - solange das Ergebnis in den Speicher
geschrieben wird. Direktes Schreiben in einen `FileOutputStream` liefert unter Java 21 eine
leere Datei (siehe `.specify/tasks.md`). Der native Modus konvertiert daher in-memory:

```bash
java -cp gdv-client/target/classes de.prometheus.gdv.Main \
  --native --gdv-home /opt/gdv/GDVTools \
  --in beispiel.xml --out beispiel.snn --release 2003
```

Damit wird kein Java-8-Prozess benoetigt; die GDV-Klassen werden in einem eigenen
ClassLoader geladen. Verifiziert: Release 2003 und 2000 byte-identisch zum Java-8-Weg.

## Stream-Betrieb (ohne Temp-Dateien)

```bash
java -cp gdv-client/target/classes de.prometheus.gdv.Main \
  --gdv-home  /opt/gdv/GDVTools \
  --java8     /opt/jdk8/bin/java \
  --worker-cp gdv-worker/target/classes \
  --in        beispiel.xml \
  --out       beispiel.snn \
  --release   2003
```

Protokoll des Workers:
`CONVERT <release> <XML|SNN> <len>` + Nutzdaten -> `OK <len>` + Nutzdaten oder `ERR <text>`.

## Bibliothek

```java
try (GdvBridge bridge = new GdvBridge(Path.of("/opt/gdv/GDVTools"), Path.of("/opt/jdk8/bin/java"),
                                      null, List.of(Path.of("gdv-worker/target/classes")))) {
    bridge.convertFile(in, out, "2000");
    byte[] snn = bridge.convertStream(xmlBytes, "2000", Direction.XML_TO_SNN);
}
```
