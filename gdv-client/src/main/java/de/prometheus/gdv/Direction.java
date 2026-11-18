package de.prometheus.gdv;

public enum Direction {
    XML_TO_SNN(".xml", ".snn"),
    SNN_TO_XML(".snn", ".xml");

    private final String inExt;
    private final String outExt;

    Direction(String inExt, String outExt) {
        this.inExt = inExt;
        this.outExt = outExt;
    }

    public String inExt() { return inExt; }
    public String outExt() { return outExt; }

    public static Direction of(String fileName) {
        String n = fileName.toLowerCase();
        if (n.endsWith(".xml")) return XML_TO_SNN;
        if (n.endsWith(".snn")) return SNN_TO_XML;
        throw new IllegalArgumentException("Unbekannte Endung: " + fileName);
    }
}
