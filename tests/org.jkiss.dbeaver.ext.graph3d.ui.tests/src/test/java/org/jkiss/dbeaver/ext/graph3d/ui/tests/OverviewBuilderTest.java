/*
 * DBeaver 3D Schema Graph
 * Licensed under EPL-2.0.
 */
package org.jkiss.dbeaver.ext.graph3d.ui.tests;

import org.jkiss.dbeaver.ext.graph3d.ui.GraphModel;
import org.jkiss.dbeaver.ext.graph3d.ui.model.GraphBuilder;
import org.jkiss.dbeaver.ext.graph3d.ui.model.OverviewBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link OverviewBuilder} — the schema-overview aggregation.
 * Pure, headless. Builds a table-level graph with the fake source, then rolls
 * it up and asserts the schema super-nodes and inter-schema edges.
 */
class OverviewBuilderTest {

    @Test
    @DisplayName("one super-node per schema, labeled with its table count")
    void superNodesPerSchema() {
        GraphModel tables = GraphBuilder.build(FakeGraphSource.create()
            .entityInSchema("db.sales.orders", "orders", "sales")
            .entityInSchema("db.sales.items", "items", "sales")
            .entityInSchema("db.hr.employees", "employees", "hr"));

        GraphModel ov = OverviewBuilder.build(tables);

        assertEquals(2, ov.getNodes().size(), "two distinct schemas -> two super-nodes");
        GraphModel.Node sales = ov.getNodes().get(0);
        assertEquals("sales", sales.schema());
        assertEquals("sales (2)", sales.name(), "label carries the table count");
        assertTrue(sales.path().startsWith(OverviewBuilder.SCHEMA_PATH_PREFIX),
            "super-node path is tagged so the view can expand it");
        assertEquals("hr (1)", ov.getNodes().get(1).name());
    }

    @Test
    @DisplayName("cross-schema FKs roll up into weighted inter-schema edges")
    void crossSchemaEdgesAggregate() {
        // Two FKs from sales -> hr should collapse to a single weight-2 edge.
        GraphModel tables = GraphBuilder.build(FakeGraphSource.create()
            .entityInSchema("db.sales.orders", "orders", "sales")
            .entityInSchema("db.sales.invoices", "invoices", "sales")
            .entityInSchema("db.hr.employees", "employees", "hr")
            .fk("db.sales.orders", "db.hr.employees")
            .fk("db.sales.invoices", "db.hr.employees"));

        GraphModel ov = OverviewBuilder.build(tables);

        assertEquals(1, ov.getEdges().size(), "both cross-schema FKs collapse to one overview edge");
        GraphModel.Edge e = ov.getEdges().get(0);
        assertEquals(2, e.weight(), "edge weight = number of underlying crossing FKs");
        assertEquals(ov.getNodes().get(0).id(), e.source());
        assertEquals(ov.getNodes().get(1).id(), e.target());
    }

    @Test
    @DisplayName("same-schema (internal) FKs are not drawn as overview edges")
    void internalEdgesExcluded() {
        GraphModel tables = GraphBuilder.build(FakeGraphSource.create()
            .entityInSchema("db.sales.orders", "orders", "sales")
            .entityInSchema("db.sales.customers", "customers", "sales")
            .fk("db.sales.orders", "db.sales.customers"));

        GraphModel ov = OverviewBuilder.build(tables);

        assertEquals(1, ov.getNodes().size());
        assertEquals(0, ov.getEdges().size(), "an intra-schema FK is not an overview edge");
    }

    @Test
    @DisplayName("tables with no schema fold into a single (default) super-node")
    void blankSchemaGrouped() {
        GraphModel tables = GraphBuilder.build(FakeGraphSource.create()
            .entity("t1", "t1")
            .entity("t2", "t2"));

        GraphModel ov = OverviewBuilder.build(tables);

        assertEquals(1, ov.getNodes().size());
        assertEquals("(default) (2)", ov.getNodes().get(0).name());
    }
}
