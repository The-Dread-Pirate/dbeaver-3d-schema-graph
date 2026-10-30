/*
 * DBeaver 3D Schema Graph
 * Licensed under EPL-2.0.
 */
package org.jkiss.dbeaver.ext.graph3d.ui;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.graph3d.ui.model.GraphBuilder;
import org.jkiss.dbeaver.ext.graph3d.ui.model.GraphSource;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSEntityAssociation;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a DBeaver {@link DBSObjectContainer} (schema/catalog) through the
 * public model API and produces a {@link GraphModel}. The traversal mirrors
 * {@code org.jkiss.dbeaver.model.erd.ERDUtils.collectDatabaseTables}: cache the
 * container's structure, enumerate entities, resolve each entity's foreign-key
 * associations.
 *
 * <p>This class is only the <em>adapter</em>: it flattens DBeaver's metadata
 * into the provider-agnostic {@link GraphSource} shape and hands it to the pure
 * {@link GraphBuilder}, which carries all graph-shaping logic and is unit-tested.
 *
 * <p><b>Bounded by default.</b> A schema can hold thousands of tables, and
 * caching every entity's attributes + associations is a burst of catalog reads
 * against the live database. To stay safe on large/production schemas the read
 * is capped at {@link #DEFAULT_MAX_TABLES} entities and checks the
 * {@link DBRProgressMonitor} for cancellation between entities, so closing the
 * view (or Progress-view cancel) aborts promptly. The {@link ExtractResult}
 * reports whether the cap truncated the schema so the UI can say so.
 */
public final class SchemaMetadataExtractor {

    /** Default table cap: enough for a readable graph, small enough to be a
     *  fast, cancellable catalog read even on a very large production schema. */
    public static final int DEFAULT_MAX_TABLES = 500;

    private SchemaMetadataExtractor() {
    }

    /** Result of an extract: the graph plus how far the read actually went. */
    public record ExtractResult(GraphModel model, int includedTables,
                                int totalTables, boolean truncated, boolean cancelled) {
    }

    /**
     * Extract with the default cap. Convenience for callers that only want the
     * graph; see {@link #extract(DBRProgressMonitor, DBSObjectContainer, int)}
     * for the full result with truncation info.
     */
    public static GraphModel extract(DBRProgressMonitor monitor, DBSObjectContainer container)
            throws DBException {
        return extract(monitor, container, DEFAULT_MAX_TABLES).model();
    }

    /**
     * Extract tables (nodes) and foreign keys (edges) from a container, reading
     * at most {@code maxTables} entities.
     *
     * @param monitor   progress monitor; cancellation is honored between entities
     * @param container the schema/catalog to read
     * @param maxTables cap on entities read ({@literal <=} 0 means
     *                  {@link #DEFAULT_MAX_TABLES})
     * @throws DBException if metadata cannot be read
     */
    public static ExtractResult extract(DBRProgressMonitor monitor, DBSObjectContainer container,
                                        int maxTables) throws DBException {
        int cap = maxTables > 0 ? maxTables : DEFAULT_MAX_TABLES;

        container.cacheStructure(monitor,
            DBSObjectContainer.STRUCT_ENTITIES
                | DBSObjectContainer.STRUCT_ATTRIBUTES
                | DBSObjectContainer.STRUCT_ASSOCIATIONS);

        List<GraphSource.SourceEntity> entities = new ArrayList<>();
        Map<String, List<GraphSource.SourceAssociation>> assocByFullId = new HashMap<>();

        int totalEntities = 0;
        boolean truncated = false;
        boolean cancelled = false;

        Collection<? extends DBSObject> children = container.getChildren(monitor);
        if (children != null) {
            for (DBSObject child : children) {
                if (!(child instanceof DBSEntity entity)) {
                    continue;
                }
                totalEntities++;

                if (entities.size() >= cap) {
                    truncated = true;
                    continue; // keep counting totalEntities, but read no more
                }
                if (monitor.isCanceled()) {
                    cancelled = true;
                    break;
                }

                String sourceFullId = DBUtils.getObjectFullId(entity);
                entities.add(new GraphSource.SourceEntity(sourceFullId, entity.getName()));

                List<GraphSource.SourceAssociation> fks = new ArrayList<>();
                Collection<? extends DBSEntityAssociation> associations = entity.getAssociations(monitor);
                if (associations != null) {
                    for (DBSEntityAssociation fk : associations) {
                        DBSEntity referenced = fk.getAssociatedEntity();
                        if (referenced == null) {
                            continue;
                        }
                        fks.add(new GraphSource.SourceAssociation(
                            sourceFullId, DBUtils.getObjectFullId(referenced)));
                    }
                }
                assocByFullId.put(sourceFullId, fks);
            }
        }

        GraphSource source = new GraphSource() {
            @Override
            public List<SourceEntity> entities() {
                return entities;
            }

            @Override
            public List<SourceAssociation> associationsOf(String entityFullId) {
                return assocByFullId.getOrDefault(entityFullId, List.of());
            }
        };

        GraphModel model = GraphBuilder.build(source);
        return new ExtractResult(model, entities.size(), totalEntities, truncated, cancelled);
    }
}
