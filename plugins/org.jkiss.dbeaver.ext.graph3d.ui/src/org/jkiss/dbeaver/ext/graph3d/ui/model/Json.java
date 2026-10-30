/*
 * DBeaver 3D Schema Graph
 * Licensed under EPL-2.0.
 */
package org.jkiss.dbeaver.ext.graph3d.ui.model;

/**
 * Minimal, dependency-free JSON helpers. The graph/detail payloads have a small
 * fixed shape, so a full JSON library is unwarranted; this centralizes the one
 * subtle part -- escaping a Java string into a JSON string literal -- so both
 * {@code GraphModel} and {@code NodeDetails} share a single tested routine.
 */
public final class Json {

    private Json() {
    }

    /** Quote and escape a value as a JSON string literal (null -&gt; {@code ""}). */
    public static String quote(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
        return b.toString();
    }
}
