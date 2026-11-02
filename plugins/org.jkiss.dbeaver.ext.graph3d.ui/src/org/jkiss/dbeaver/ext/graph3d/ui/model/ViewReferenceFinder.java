/*
 * DBeaver 3D Schema Graph
 * Licensed under EPL-2.0.
 */
package org.jkiss.dbeaver.ext.graph3d.ui.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure, dependency-free extraction of table references from a view's SQL
 * definition (Option B: identifier name-matching against the set of tables
 * already known in the container).
 *
 * <p>This is deliberately NOT a SQL parser. It tokenizes the view SQL into
 * identifier-ish words, strips string/comment noise first so literals can't
 * masquerade as table names, then keeps only the words that match a table name
 * that actually exists in the container. Because it can only ever return names
 * from the known set, it cannot invent a reference to a non-existent table; its
 * failure mode is a false positive when a column (or alias) happens to share a
 * table's name, which is acceptable for a best-effort "views connect to their
 * sources" visualization. The accuracy ceiling here is why the seam is kept
 * clean: a DBeaver semantic-parser implementation (Option A) can replace this
 * class without touching the extractor or the graph model.
 *
 * <p>Matching is case-insensitive (SQL identifiers usually are) and a view is
 * never linked to itself.
 */
public final class ViewReferenceFinder {

    private ViewReferenceFinder() {
    }

    /**
     * Find the full ids of tables referenced by {@code viewSql}.
     *
     * @param viewSql       the view's SQL/DDL definition text (may be null/blank)
     * @param nameToFullId  lower-cased table/view name -> full id, for every
     *                      entity in the container (both the simple name and,
     *                      where useful, the qualified name may be registered
     *                      by the caller so either form in the SQL matches)
     * @param selfFullId    the view's own full id, excluded from the result
     * @return distinct referenced full ids, in first-seen order
     */
    public static List<String> findReferencedFullIds(
            String viewSql, Map<String, String> nameToFullId, String selfFullId) {
        if (viewSql == null || viewSql.isBlank() || nameToFullId.isEmpty()) {
            return List.of();
        }
        String cleaned = stripLiteralsAndComments(viewSql);
        Set<String> result = new LinkedHashSet<>();
        for (String token : tokenizeIdentifiers(cleaned)) {
            String key = token.toLowerCase(Locale.ROOT);
            String fullId = nameToFullId.get(key);
            if (fullId != null && !fullId.equals(selfFullId)) {
                result.add(fullId);
            }
        }
        return new ArrayList<>(result);
    }

    /**
     * Remove single-quoted strings, double-quoted-string literals, and SQL
     * comments ({@code -- ...} to end of line and {@code /* ... *&#47;} blocks),
     * so their contents can't be mistaken for identifiers. Double-quoted text
     * is treated as a quoted IDENTIFIER (kept, with quotes dropped) rather than
     * a string, because standard SQL uses double quotes for identifiers.
     */
    static String stripLiteralsAndComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            // Line comment: -- to end of line.
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            // Block comment: /* ... */
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2; // skip the closing */ (safe if we ran off the end)
                out.append(' ');
                continue;
            }
            // Single-quoted string literal: drop entirely.
            if (c == '\'') {
                i++;
                while (i < n) {
                    char d = sql.charAt(i);
                    if (d == '\'') {
                        // Escaped '' inside a string stays inside the string.
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                out.append(' ');
                continue;
            }
            // Double-quoted identifier: keep the inner text as a bare word.
            if (c == '"') {
                i++;
                while (i < n && sql.charAt(i) != '"') {
                    out.append(sql.charAt(i));
                    i++;
                }
                i++; // skip closing quote
                out.append(' ');
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * Split into identifier tokens. An identifier is a maximal run of letters,
     * digits, underscores and dots; a dotted name like {@code schema.orders} is
     * split so each part (and the whole) can be matched against the known set.
     */
    static List<String> tokenizeIdentifiers(String text) {
        List<String> tokens = new ArrayList<>();
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (isIdentChar(c)) {
                int start = i;
                while (i < n && isIdentChar(text.charAt(i))) {
                    i++;
                }
                String word = text.substring(start, i);
                tokens.add(word);
                // Also emit each dot-separated part (schema.table -> table).
                if (word.indexOf('.') >= 0) {
                    for (String part : word.split("\\.")) {
                        if (!part.isEmpty()) {
                            tokens.add(part);
                        }
                    }
                }
            } else {
                i++;
            }
        }
        return tokens;
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.';
    }
}
