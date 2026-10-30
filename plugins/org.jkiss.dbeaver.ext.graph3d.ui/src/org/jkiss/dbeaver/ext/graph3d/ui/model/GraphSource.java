/*
 * DBeaver 3D Schema Graph
 * Licensed under EPL-2.0.
 */
package org.jkiss.dbeaver.ext.graph3d.ui.model;

import java.util.List;

/**
 * Provider-agnostic view of the metadata needed to build a schema graph.
 *
 * <p>This is the single seam between DBeaver's model API and the pure
 * {@link GraphBuilder} algorithm. DBeaver's adapter
 * ({@code SchemaMetadataExtractor}) implements this interface in terms of
 * {@code DBSObjectContainer}/{@code DBSEntity}/{@code DBSEntityAssociation};
 * tests implement it with trivial in-memory fakes. Because the builder depends
 * only on this interface, the graph-building logic is unit-testable without any
 * DBeaver, Eclipse or OSGi runtime.
 *
 * <p>An entity's identity is its {@link SourceEntity#fullId()} string -- stable,
 * unique within a datasource, and the value used both to deduplicate nodes and
 * to resolve a node back to its database object for navigation.
 */
public interface GraphSource {

    /** A table/entity: a stable full id plus a display name. */
    record SourceEntity(String fullId, String name) {
    }

    /**
     * A foreign-key association originating at one entity and referencing
     * another, each identified by full id. {@code targetFullId} may point at an
     * entity not present in {@link #entities()} (e.g. a cross-schema FK); the
     * builder decides how to treat such dangling references.
     */
    record SourceAssociation(String sourceFullId, String targetFullId) {
    }

    /**
     * All entities in this container, in a deterministic order. The order is
     * preserved as the graph's node order, so a stable source order yields a
     * stable, reproducible {@code GraphModel}.
     */
    List<SourceEntity> entities();

    /**
     * All foreign-key associations whose <em>source</em> is {@code entityFullId}.
     * Returning an empty list for an unknown id is acceptable.
     */
    List<SourceAssociation> associationsOf(String entityFullId);
}
