/*
 * DBeaver 3D Schema Graph
 * Licensed under EPL-2.0.
 */
package org.jkiss.dbeaver.ext.graph3d.ui.tests;

import org.jkiss.dbeaver.ext.graph3d.ui.GraphModel;
import org.jkiss.dbeaver.ext.graph3d.ui.model.GraphBuilder;
import org.jkiss.dbeaver.ext.graph3d.ui.model.GraphSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link GraphBuilder} -- the only non-trivial logic in the
 * plugin. These run headless with no DBeaver/Eclipse/OSGi runtime.
 */
class GraphBuilderTest {

    private static GraphModel.Node node(GraphModel m, int id) {
        return m.getNodes().get(id);
    }

    @Test
    @DisplayName("empty source yields empty graph and valid JSON")
    void empty() {
        GraphModel m = GraphBuilder.build(FakeGraphSource.create());
        assertEquals(0, m.getNodes().size());
        assertEquals(0, m.getEdges().size());
        assertEquals("{\"nodes\":[],\"edges\":[]}", m.toJson());
        // (empty graph has no edges, so the new edge "kind" field never appears)
    }

    @Test
    @DisplayName("nodes keep entities() order; ids are the dense 0-based index")
    void nodeOrderAndIds() {
        GraphModel m = GraphBuilder.build(FakeGraphSource.create()
            .entity("db.s.orders", "orders")
            .entity("db.s.customers", "customers")
            .entity("db.s.items", "items"));

        assertEquals(3, m.getNodes().size());
        assertEquals("orders", node(m, 0).name());
        assertEquals("customers", node(m, 1).name());
        assertEquals("items", node(m, 2).name());
        for (int i = 0; i < 3; i++) {
            assertEquals(i, node(m, i).id());
        }
    }

    @Test
    @DisplayName("a simple FK becomes one edge with correct source/target ids")
    void simpleForeignKey() {
        // orders(0) -> customers(1)
        GraphModel m = GraphBuilder.build(FakeGraphSource.create()
            .entity("orders")
            .entity("customers")
            .fk("orders", "customers"));

        assertEquals(1, m.getEdges().size());
        GraphModel.Edge e = m.getEdges().get(0);
        assertEquals(0, e.source());
        assertEquals(1, e.target());
        assertEquals(1, e.weight());
    }

    @Test
    @DisplayName("degree counts: out on the referencing side, in on the referenced side")
    void degreeCounts() {
        // orders -> customers, items -> orders
        GraphModel m = GraphBuilder.build(FakeGraphSource.create()
            .entity("orders")
            .entity("customers")
            .entity("items")
            .fk("orders", "customers")
            .fk("items", "orders"));

        GraphModel.Node orders = node(m, 0);
        GraphModel.Node customers = node(m, 1);
        GraphModel.Node items = node(m, 2);

        assertEquals(1, orders.outCount());  // -> customers
        assertEquals(1, orders.inCount());   // <- items
        assertEquals(1, customers.inCount());
        assertEquals(0, customers.outCount());
        assertEquals(1, items.outCount());
        assertEquals(0, items.inCount());
    }

    @Test
    @DisplayName("self-referential FK yields a self-edge and bumps both in and out")
    void selfReference() {
        // employee.manager_id -> employee
        GraphModel m = GraphBuilder.build(FakeGraphSource.create()
            .entity("employee")
            .fk("employee", "employee"));

        assertEquals(1, m.getEdges().size());
        GraphModel.Edge e = m.getEdges().get(0);
        assertEquals(0, e.source());
        assertEquals(0, e.target());

        GraphModel.Node emp = node(m, 0);
        assertEquals(1, emp.outCount());
        assertEquals(1, emp.inCount());
    }

    @Test
    @DisplayName("cross-container FK is counted but not drawn as an edge")
    void crossContainerForeignKey() {
        // local.orders -> external.customers (external not in this container)
        GraphModel m = GraphBuilder.build(FakeGraphSource.create()
            .entity("local.orders")
            .fk("local.orders", "external.customers"));

        assertEquals(0, m.getEdges().size(), "dangling target must not produce an edge");
        GraphModel.Node orders = node(m, 0);
        assertEquals(1, orders.outCount(), "declared FK still counts toward out-degree");
        assertEquals(1, orders.unresolvedCount(), "dangling FK recorded as unresolved");
    }

    @Test
    @DisplayName("multiple FKs between the same pair produce multiple edges and counts")
    void parallelForeignKeys() {
        // orders -> customers via both bill_to and ship_to
        GraphModel m = GraphBuilder.build(FakeGraphSource.create()
            .entity("orders")
            .entity("customers")
            .fk("orders", "customers")
            .fk("orders", "customers"));

        assertEquals(2, m.getEdges().size());
        assertEquals(2, node(m, 0).outCount());
        assertEquals(2, node(m, 1).inCount());
        for (GraphModel.Edge e : m.getEdges()) {
            assertEquals(0, e.source());
            assertEquals(1, e.target());
        }
    }

    @Test
    @DisplayName("duplicate entity fullId keeps the first occurrence, no duplicate node")
    void duplicateEntityId() {
        GraphModel m = GraphBuilder.build(FakeGraphSource.create()
            .entity("t", "first")
            .entity("t", "second"));

        assertEquals(1, m.getNodes().size());
        assertEquals("first", node(m, 0).name());
    }

    @Test
    @DisplayName("null target in an association is skipped defensively")
    void nullTargetSkipped() {
        GraphModel m = GraphBuilder.build(FakeGraphSource.create()
            .entity("a")
            .rawAssociation("a", new GraphSource.SourceAssociation("a", null)));

        assertEquals(0, m.getEdges().size());
        // A null target is not even a declared FK target -> not counted.
        assertEquals(0, node(m, 0).outCount());
        assertEquals(0, node(m, 0).unresolvedCount());
    }

    @Test
    @DisplayName("JSON serialization escapes special characters in names")
    void jsonEscaping() {
        GraphModel m = GraphBuilder.build(FakeGraphSource.create()
            .entity("id1", "quote\"tab\tbackslash\\"));

        String json = m.toJson();
        assertTrue(json.contains("quote\\\"tab\\tbackslash\\\\"),
            "expected escaped name in JSON, got: " + json);
    }

    @Test
    @DisplayName("end-to-end JSON shape for a small graph is exact and stable")
    void endToEndJson() {
        GraphModel m = GraphBuilder.build(FakeGraphSource.create()
            .entity("A", "A")
            .entity("B", "B")
            .fk("A", "B"));

        String expected =
            "{\"nodes\":["
            + "{\"id\":0,\"path\":\"A\",\"name\":\"A\",\"inCount\":0,\"outCount\":1,\"unresolvedCount\":0},"
            + "{\"id\":1,\"path\":\"B\",\"name\":\"B\",\"inCount\":1,\"outCount\":0,\"unresolvedCount\":0}"
            + "],\"edges\":["
            + "{\"source\":0,\"target\":1,\"weight\":1,\"kind\":\"fk\"}"
            + "]}";
        assertEquals(expected, m.toJson());
    }

    @Test
    @DisplayName("a view-reference association becomes an edge of kind \"view\"")
    void viewReferenceEdge() {
        // view_orders references table orders in its definition
        GraphModel m = GraphBuilder.build(FakeGraphSource.create()
            .entity("v_orders", "v_orders")
            .entity("orders", "orders")
            .viewRef("v_orders", "orders"));

        assertEquals(1, m.getEdges().size());
        GraphModel.Edge e = m.getEdges().get(0);
        assertEquals(0, e.source());
        assertEquals(1, e.target());
        assertEquals(GraphModel.KIND_VIEW, e.kind());
        // The view's reference counts toward its out-degree like any edge.
        assertEquals(1, node(m, 0).outCount());
        assertEquals(1, node(m, 1).inCount());
    }

    @Test
    @DisplayName("fk and view edges coexist, each keeping its own kind")
    void mixedEdgeKinds() {
        GraphModel m = GraphBuilder.build(FakeGraphSource.create()
            .entity("orders").entity("customers").entity("v_sales")
            .fk("orders", "customers")
            .viewRef("v_sales", "orders"));

        assertEquals(2, m.getEdges().size());
        GraphModel.Edge fk = m.getEdges().get(0);
        GraphModel.Edge view = m.getEdges().get(1);
        assertEquals(GraphModel.KIND_FK, fk.kind());
        assertEquals(GraphModel.KIND_VIEW, view.kind());
    }

    @Test
    @DisplayName("a larger graph has consistent edge count and total degree")
    void invariants() {
        FakeGraphSource src = FakeGraphSource.create()
            .entity("a").entity("b").entity("c").entity("d")
            .fk("a", "b").fk("b", "c").fk("c", "d").fk("d", "a")  // cycle
            .fk("a", "c");                                        // chord
        GraphModel m = GraphBuilder.build(src);

        assertEquals(5, m.getEdges().size());

        int totalIn = 0, totalOut = 0;
        for (GraphModel.Node n : m.getNodes()) {
            totalIn += n.inCount();
            totalOut += n.outCount();
        }
        // Every drawn edge contributes exactly one out and one in.
        assertEquals(5, totalOut);
        assertEquals(5, totalIn);

        List<GraphModel.Edge> edges = m.getEdges();
        for (GraphModel.Edge e : edges) {
            assertTrue(e.source() >= 0 && e.source() < 4);
            assertTrue(e.target() >= 0 && e.target() < 4);
        }
    }
}
