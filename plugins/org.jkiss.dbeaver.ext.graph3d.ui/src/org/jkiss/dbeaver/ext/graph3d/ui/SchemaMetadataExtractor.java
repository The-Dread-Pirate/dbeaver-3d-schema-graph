/*
 * DBeaver 3D Schema Graph
 * Licensed under EPL-2.0.
 */
package org.jkiss.dbeaver.ext.graph3d.ui;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.graph3d.ui.model.GraphBuilder;
import org.jkiss.dbeaver.ext.graph3d.ui.model.GraphSource;
import org.jkiss.dbeaver.ext.graph3d.ui.model.ViewReferenceFinder;
import org.jkiss.dbeaver.model.DBPEvaluationContext;
import org.jkiss.dbeaver.model.DBPQualifiedObject;
import org.jkiss.dbeaver.model.DBPScriptObject;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSEntityAssociation;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;
import org.jkiss.dbeaver.model.struct.rdb.DBSTable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

        // Views we read FKs for, remembered so a second pass can parse their SQL
        // definitions for referenced tables once every entity name is known.
        List<DBSTable> views = new ArrayList<>();
        // Case-insensitive name -> fullId for every included entity. Both the
        // simple name and the fully-qualified name are registered so a
        // reference in either form in the view SQL resolves.
        Map<String, String> nameToFullId = new LinkedHashMap<>();

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
                registerName(nameToFullId, entity.getName(), sourceFullId);
                if (entity instanceof DBPQualifiedObject qualified) {
                    registerName(nameToFullId,
                        qualified.getFullyQualifiedName(DBPEvaluationContext.DML), sourceFullId);
                }
                if (entity instanceof DBSTable table && table.isView()) {
                    views.add(table);
                }

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

        // Second pass: for each view, read its SQL definition and name-match
        // referenced tables (Option B). Best-effort — a view whose DDL cannot be
        // read is simply left without reference edges, never failing the extract.
        if (!cancelled) {
            for (DBSTable view : views) {
                if (monitor.isCanceled()) {
                    cancelled = true;
                    break;
                }
                if (!(view instanceof DBPScriptObject scriptObject)) {
                    continue;
                }
                String viewFullId = DBUtils.getObjectFullId(view);
                String sql;
                try {
                    sql = scriptObject.getObjectDefinitionText(monitor, DBPScriptObject.EMPTY_OPTIONS);
                } catch (Exception e) {
                    // DDL unavailable for this view (permissions, dialect) -> skip it.
                    continue;
                }
                List<String> referenced =
                    ViewReferenceFinder.findReferencedFullIds(sql, nameToFullId, viewFullId);
                if (referenced.isEmpty()) {
                    continue;
                }
                List<GraphSource.SourceAssociation> refs =
                    assocByFullId.computeIfAbsent(viewFullId, k -> new ArrayList<>());
                for (String targetFullId : referenced) {
                    refs.add(new GraphSource.SourceAssociation(
                        viewFullId, targetFullId, GraphSource.KIND_VIEW));
                }
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

    /**
     * Register a lower-cased name -> fullId mapping for view-reference matching.
     * The first entity to claim a name wins, so a later ambiguous collision does
     * not silently steal an earlier table's name; blank names are ignored.
     */
    private static void registerName(Map<String, String> nameToFullId, String name, String fullId) {
        if (name == null || name.isBlank()) {
            return;
        }
        nameToFullId.putIfAbsent(name.toLowerCase(Locale.ROOT), fullId);
    }
}
