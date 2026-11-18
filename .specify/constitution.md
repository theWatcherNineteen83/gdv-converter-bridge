# Constitution — GDV Converter Bridge

## Prinzipien

1. **Der GDV-Code bleibt unangetastet.** Keine Patches am Jar, keine eigenen Klassen im `sn.*`-Paket.
2. **Prozessgrenze statt Reflection-Hacks.** Der Konverter läuft in einem eigenen Java-8-Prozess;
   der Java-21-Teil kennt nur die CLI-Schnittstelle.
3. **Ein Ergebnis, das man prüfen kann.** Jeder Lauf liefert Exit-Code, `erg.txt`-Inhalt und
   eine Existenz-/Größenprüfung der Ausgabedatei.
4. **Keine Lizenzweitergabe.** GDVTools wird nicht ins Repo committet (GDV-Copyright);
   Pfad ist konfigurierbar.

## Tech-Stack

- Java 21 (Client, Maven), Java 8 (Konverter-Laufzeit, extern)
- Keine zusätzlichen Laufzeit-Abhängigkeiten im Client
