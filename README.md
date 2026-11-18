# GDV Converter Bridge

Ruft den GDV-XMLKonverter (GDVTools, `sn.SN_XMLKonverter`) aus einem Java-21-Programm auf.
Der Konverter selbst läuft in einem **Java-8-Prozess**, weil die GDV-Jars gegen eine ältere
JDK-Generation gebaut sind (Bytecode 51 = Java 7).

## Status

Prototyp, Stufe 1 (Dateien) lauffähig, Stufe 2 (Streams) über temporäre Dateien.
Siehe `.specify/spec.md`, `.specify/plan.md`, `.specify/tasks.md`.

## Voraussetzungen

- JDK 21 (Client)
- JDK 8 (Konverter-Prozess), z. B. `/opt/jdk8`
- GDVTools entpackt, z. B. `/opt/gdv/GDVTools`

## Aufruf

```bash
java -cp gdv-client/target/classes de.prometheus.gdv.Main \
  --gdv-home /opt/gdv/GDVTools \
  --java8    /opt/jdk8/bin/java \
  --in       beispiel.xml \
  --out      beispiel.snn \
  --release  2000
```

Richtung ergibt sich aus der Endung der Eingabedatei (`.xml` → `.snn`, `.snn` → `.xml`).

## Bibliothek

```java
GdvBridge bridge = new GdvBridge(Path.of("/opt/gdv/GDVTools"), Path.of("/opt/jdk8/bin/java"));
bridge.convertFile(in, out, "2000");
byte[] snn = bridge.convertStream(xmlBytes, "2000", Direction.XML_TO_SNN);
```
