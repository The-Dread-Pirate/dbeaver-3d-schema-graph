/*
 * DBeaver 3D Schema Graph
 * Licensed under EPL-2.0.
 */
package org.jkiss.dbeaver.ext.graph3d.ui.tests;

import org.jkiss.dbeaver.ext.graph3d.ui.model.ViewReferenceFinder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ViewReferenceFinder} -- the Option B name-matching
 * table-reference extractor. Pure, headless, no DBeaver runtime. The lookup
 * maps lower-cased table name -> full id, mirroring what the extractor builds.
 */
class ViewReferenceFinderTest {

    /** name(lower) -> fullId for a tiny schema of orders/customers/items. */
    private static Map<String, String> schema() {
        return Map.of(
            "orders", "db.s.orders",
            "customers", "db.s.customers",
            "items", "db.s.items");
    }

    @Test
    @DisplayName("a simple SELECT ... FROM resolves the referenced table")
    void simpleSelect() {
        List<String> refs = ViewReferenceFinder.findReferencedFullIds(
            "SELECT id, total FROM orders", schema(), "db.s.v_orders");
        assertEquals(List.of("db.s.orders"), refs);
    }

    @Test
    @DisplayName("a JOIN across two known tables resolves both, in first-seen order")
    void joinTwoTables() {
        List<String> refs = ViewReferenceFinder.findReferencedFullIds(
            "SELECT * FROM orders o JOIN customers c ON o.cust_id = c.id",
            schema(), "db.s.v_sales");
        assertEquals(List.of("db.s.orders", "db.s.customers"), refs);
    }

    @Test
    @DisplayName("a name appearing only inside a string literal is NOT matched")
    void ignoresStringLiterals() {
        List<String> refs = ViewReferenceFinder.findReferencedFullIds(
            "SELECT 'orders placed' AS label FROM customers", schema(), "db.s.v");
        assertEquals(List.of("db.s.customers"), refs,
            "the word orders inside a quoted string must not count as a reference");
    }

    @Test
    @DisplayName("a name appearing only inside a comment is NOT matched")
    void ignoresComments() {
        String sql = "SELECT * FROM customers -- joins orders later\n/* items pending */";
        List<String> refs = ViewReferenceFinder.findReferencedFullIds(sql, schema(), "db.s.v");
        assertEquals(List.of("db.s.customers"), refs);
    }

    @Test
    @DisplayName("the view never references itself")
    void excludesSelf() {
        // A view named like a known table (contrived) still must not self-link.
        Map<String, String> s = Map.of("orders", "db.s.orders", "v", "db.s.v");
        List<String> refs = ViewReferenceFinder.findReferencedFullIds(
            "SELECT * FROM orders, v", s, "db.s.v");
        assertEquals(List.of("db.s.orders"), refs);
    }

    @Test
    @DisplayName("schema-qualified names resolve on the table part")
    void qualifiedName() {
        List<String> refs = ViewReferenceFinder.findReferencedFullIds(
            "SELECT * FROM s.orders", schema(), "db.s.v");
        assertTrue(refs.contains("db.s.orders"));
    }

    @Test
    @DisplayName("double-quoted identifiers are matched as identifiers")
    void quotedIdentifier() {
        List<String> refs = ViewReferenceFinder.findReferencedFullIds(
            "SELECT * FROM \"orders\"", schema(), "db.s.v");
        assertEquals(List.of("db.s.orders"), refs);
    }

    @Test
    @DisplayName("duplicate references are de-duplicated")
    void deduplicates() {
        List<String> refs = ViewReferenceFinder.findReferencedFullIds(
            "SELECT * FROM orders UNION ALL SELECT * FROM orders", schema(), "db.s.v");
        assertEquals(List.of("db.s.orders"), refs);
    }

    @Test
    @DisplayName("null/blank SQL and empty schema yield no references")
    void emptyInputs() {
        assertTrue(ViewReferenceFinder.findReferencedFullIds(null, schema(), "x").isEmpty());
        assertTrue(ViewReferenceFinder.findReferencedFullIds("   ", schema(), "x").isEmpty());
        assertTrue(ViewReferenceFinder.findReferencedFullIds(
            "SELECT * FROM orders", Map.of(), "x").isEmpty());
    }

    @Test
    @DisplayName("case-insensitive matching (SQL keywords/names vary in case)")
    void caseInsensitive() {
        List<String> refs = ViewReferenceFinder.findReferencedFullIds(
            "select * from ORDERS", schema(), "db.s.v");
        assertEquals(List.of("db.s.orders"), refs);
    }

    @Test
    @DisplayName("an unknown table name simply does not resolve")
    void unknownTableIgnored() {
        List<String> refs = ViewReferenceFinder.findReferencedFullIds(
            "SELECT * FROM audit_log JOIN orders ON true", schema(), "db.s.v");
        assertEquals(List.of("db.s.orders"), refs);
        assertFalse(refs.contains("audit_log"));
    }
}
