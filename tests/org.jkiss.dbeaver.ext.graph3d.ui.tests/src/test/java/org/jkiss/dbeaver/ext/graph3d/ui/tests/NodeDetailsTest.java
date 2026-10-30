/*
 * DBeaver 3D Schema Graph
 * Licensed under EPL-2.0.
 */
package org.jkiss.dbeaver.ext.graph3d.ui.tests;

import org.jkiss.dbeaver.ext.graph3d.ui.model.Json;
import org.jkiss.dbeaver.ext.graph3d.ui.model.NodeDetails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure {@link NodeDetails} payload and the shared
 * {@link Json} escaping helper.
 */
class NodeDetailsTest {

    @Test
    @DisplayName("Json.quote escapes the JSON-illegal characters")
    void jsonQuote() {
        assertEquals("\"\"", Json.quote(null));
        assertEquals("\"plain\"", Json.quote("plain"));
        assertEquals("\"a\\\"b\"", Json.quote("a\"b"));
        assertEquals("\"a\\\\b\"", Json.quote("a\\b"));
        assertEquals("\"a\\nb\"", Json.quote("a\nb"));
        assertEquals("\"a\\tb\"", Json.quote("a\tb"));
        assertEquals("\"\\u0001\"", Json.quote("\u0001"));
    }

    @Test
    @DisplayName("empty details produce a valid, stable JSON object")
    void emptyDetails() {
        NodeDetails d = new NodeDetails("db.s.orders", "orders", "TABLE", null, null);
        assertEquals(
            "{\"fullId\":\"db.s.orders\",\"name\":\"orders\",\"type\":\"TABLE\","
                + "\"attributes\":[],\"foreignKeys\":[]}",
            d.toJson());
    }

    @Test
    @DisplayName("attributes and foreign keys serialize in order with correct types")
    void fullDetails() {
        NodeDetails d = new NodeDetails(
            "db.s.orders", "orders", "TABLE",
            List.of(
                new NodeDetails.Attribute("id", "INTEGER", false, "PRIMARY"),
                new NodeDetails.Attribute("customer_id", "INTEGER", true, "")),
            List.of(
                new NodeDetails.ForeignKey("fk_customer", "customers", "customer_id -> id")));

        String json = d.toJson();
        String expected =
            "{\"fullId\":\"db.s.orders\",\"name\":\"orders\",\"type\":\"TABLE\","
            + "\"attributes\":["
            + "{\"name\":\"id\",\"type\":\"INTEGER\",\"nullable\":false,\"keyType\":\"PRIMARY\"},"
            + "{\"name\":\"customer_id\",\"type\":\"INTEGER\",\"nullable\":true,\"keyType\":\"\"}"
            + "],\"foreignKeys\":["
            + "{\"name\":\"fk_customer\",\"refTable\":\"customers\",\"columns\":\"customer_id -> id\"}"
            + "]}";
        assertEquals(expected, json);
    }

    @Test
    @DisplayName("details escape special characters in every string field")
    void escapingInDetails() {
        NodeDetails d = new NodeDetails(
            "id\"x", "name\\y", "TA\tB",
            List.of(new NodeDetails.Attribute("c\n1", "VARCHAR", true, "")),
            List.of());
        String json = d.toJson();
        assertTrue(json.contains("\"id\\\"x\""));
        assertTrue(json.contains("\"name\\\\y\""));
        assertTrue(json.contains("\"TA\\tB\""));
        assertTrue(json.contains("\"c\\n1\""));
    }

    @Test
    @DisplayName("defensive copies: mutating the source lists does not change the payload")
    void defensiveCopy() {
        java.util.List<NodeDetails.Attribute> attrs = new java.util.ArrayList<>();
        attrs.add(new NodeDetails.Attribute("id", "INT", false, "PRIMARY"));
        NodeDetails d = new NodeDetails("f", "n", "TABLE", attrs, List.of());
        attrs.clear();
        assertEquals(1, d.attributes().size(), "NodeDetails must copy the attribute list");
    }
}
