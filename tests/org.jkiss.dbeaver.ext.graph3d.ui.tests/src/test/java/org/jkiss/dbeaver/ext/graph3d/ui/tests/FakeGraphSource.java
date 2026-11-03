/*
 * DBeaver 3D Schema Graph
 * Licensed under EPL-2.0.
 */
package org.jkiss.dbeaver.ext.graph3d.ui.tests;

import org.jkiss.dbeaver.ext.graph3d.ui.model.GraphSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trivial in-memory {@link GraphSource} for unit tests, with a fluent builder.
 * No DBeaver, Eclipse or OSGi types involved. Entity insertion order is
 * preserved so tests can assert deterministic node ordering, and a duplicate
 * fullId is retained in {@link #entities()} (not pre-deduplicated by the fake)
 * so the builder's own keep-first dedup path is genuinely exercised.
 */
final class FakeGraphSource implements GraphSource {

    private final List<SourceEntity> entities = new ArrayList<>();
    private final Map<String, List<SourceAssociation>> assoc = new LinkedHashMap<>();

    static FakeGraphSource create() {
        return new FakeGraphSource();
    }

    /** Add an entity; name defaults to the fullId when omitted. */
    FakeGraphSource entity(String fullId) {
        return entity(fullId, fullId);
    }

    FakeGraphSource entity(String fullId, String name) {
        entities.add(new SourceEntity(fullId, name));
        assoc.computeIfAbsent(fullId, k -> new ArrayList<>());
        return this;
    }

    /** Add an entity with an explicit containing-schema name. */
    FakeGraphSource entityInSchema(String fullId, String name, String schema) {
        entities.add(new SourceEntity(fullId, name, schema));
        assoc.computeIfAbsent(fullId, k -> new ArrayList<>());
        return this;
    }

    /** Add a foreign key sourceFullId -> targetFullId. Source need not exist yet. */
    FakeGraphSource fk(String sourceFullId, String targetFullId) {
        assoc.computeIfAbsent(sourceFullId, k -> new ArrayList<>())
             .add(new SourceAssociation(sourceFullId, targetFullId));
        return this;
    }

    /** Add a view-reference association viewFullId -> tableFullId. */
    FakeGraphSource viewRef(String viewFullId, String tableFullId) {
        assoc.computeIfAbsent(viewFullId, k -> new ArrayList<>())
             .add(new SourceAssociation(viewFullId, tableFullId, GraphSource.KIND_VIEW));
        return this;
    }

    /** Add a raw (possibly null-bearing) association, to test defensive paths. */
    FakeGraphSource rawAssociation(String sourceFullId, SourceAssociation a) {
        assoc.computeIfAbsent(sourceFullId, k -> new ArrayList<>()).add(a);
        return this;
    }

    @Override
    public List<SourceEntity> entities() {
        return new ArrayList<>(entities);
    }

    @Override
    public List<SourceAssociation> associationsOf(String entityFullId) {
        return assoc.getOrDefault(entityFullId, List.of());
    }
}
