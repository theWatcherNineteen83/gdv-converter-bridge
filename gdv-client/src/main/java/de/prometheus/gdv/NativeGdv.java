package de.prometheus.gdv;

import java.io.*;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Konvertiert direkt im Java-21-Prozess, ohne Java-8-Kindprozess.
 *
 * Die GDV-Klassen werden in einem eigenen ClassLoader geladen (GDV-Jar + lib/*), damit sie
 * nicht mit dem Anwendungsklassepfad kollidieren. Die Ausgabe geht immer in einen
 * ByteArrayOutputStream: direktes Schreiben in einen FileOutputStream liefert unter Java 21
 * eine leere Datei (siehe .specify/tasks.md, Abschnitt Java-21-Befund).
 */
public final class NativeGdv implements Closeable {

    private final Path gdvHome;
    private final URLClassLoader loader;
    private final Class<?> converterClass;
    @SuppressWarnings("unused")
    private final Object satzarten;
    private final Map<String, Object> converterCache = new HashMap<>();

    public NativeGdv(Path gdvHome) throws Exception {
        this.gdvHome = gdvHome.toAbsolutePath();
        Path jar = this.gdvHome.resolve("GDV-Schadennetz.jar");
        if (!Files.exists(jar)) throw new IllegalArgumentException("GDV-Jar fehlt: " + jar);
        List<URL> urls = new ArrayList<>();
        urls.add(jar.toUri().toURL());
        Path lib = this.gdvHome.resolve("lib");
        if (Files.isDirectory(lib)) {
            try (Stream<Path> s = Files.list(lib)) {
                s.filter(p -> p.toString().endsWith(".jar")).forEach(p -> {
                    try { urls.add(p.toUri().toURL()); } catch (IOException ignored) { }
                });
            }
        }
        this.loader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
        Class<?> satzartClass = Class.forName("gdv.nachrichten.SatzartElemente", true, loader);
        this.satzarten = satzartClass.getDeclaredConstructor().newInstance();
        this.converterClass = Class.forName("gdv.XmlGdvKonverter", true, loader);
    }

    public byte[] convert(byte[] data, String release, Direction direction) throws Exception {
        Object converter = converterCache.get(release);
        if (converter == null) {
            converter = converterClass
                    .getDeclaredConstructor(String.class, String.class, String.class)
                    .newInstance("gdv" + release + ".xsd", propertiesFile().toString(), release);
            converterCache.put(release, converter);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(8192, data.length * 3 / 4));
        try (InputStream in = new ByteArrayInputStream(data)) {
            if (direction == Direction.XML_TO_SNN) {
                Method m = converterClass.getMethod("convertToGdv", InputStream.class, OutputStream.class);
                m.invoke(converter, in, out);
            } else {
                Method m = converterClass.getMethod("convertToXml", InputStream.class, OutputStream.class, String.class);
                m.invoke(converter, in, out, release);
            }
        }
        if (out.size() == 0) {
            throw new IOException("Konverter lieferte 0 Bytes (Release " + release + ")");
        }
        return out.toByteArray();
    }

    private Path propertiesFile() throws IOException {
        Path props = Files.createTempFile("gdv-native-", ".properties");
        props.toFile().deleteOnExit();
        String content = "[GDV]\n"
                + "schemaPath=" + gdvHome.resolve("konfig").resolve("xml") + "/\n"
                + "workingPath=" + gdvHome.resolve("konfig") + "/\n"
                + "schemaPrefix=xsd:\n"
                + "nameSpaceURL=http://www.w3.org/2001/XMLSchema\n";
        Files.writeString(props, content);
        return props;
    }

    @Override
    public void close() {
        try { loader.close(); } catch (IOException ignored) { }
    }
}
