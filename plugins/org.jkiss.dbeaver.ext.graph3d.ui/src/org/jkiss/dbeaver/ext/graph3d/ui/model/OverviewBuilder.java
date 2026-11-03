/*
 * DBeaver 3D Schema Graph
 * Licensed under EPL-2.0.
 */
package org.jkiss.dbeaver.ext.graph3d.ui.model;

import org.jkiss.dbeaver.ext.graph3d.ui.GraphModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure aggregation of a table-level {@link GraphModel} into a <em>schema
 * overview</em>: one super-node per schema, with edges between schemas carrying
 * the number of underlying table-to-table relationships that cross that pair.
 *
 * <p>This is the data behind the drill-down "overview" mode. It reuses the full
 * table-level extract (so view edges and cross-schema FK resolution are already
 * baked in) and rolls it up — no extra database reads. Clicking a super-node in
 * the view expands that one schema back into its table graph via the normal
 * single-schema path.
 *
 * <p>Each super-node's {@code path} is {@code "schema:" + <schema name>} so the
 * view can recognize an overview node and resolve it back to the matching child
 * container to expand. Its {@code name} is the schema name; {@code inCount} /
 * {@code outCount} are the number of distinct schemas referencing / referenced,
 * and the node carries its own schema name in the {@code schema} field too.
 */
public final class OverviewBuilder {

    /** Prefix marking a node's path as a schema super-node (not a real object). */
    public static final String SCHEMA_PATH_PREFIX = "schema:";

    private OverviewBuilder() {
    }

    public static GraphModel build(GraphModel tableGraph) {
        GraphModel overview = new GraphModel();

        List<GraphModel.Node> tableNodes = tableGraph.getNodes();

        // Distinct schemas in first-seen order -> dense super-node id.
        Map<String, Integer> schemaId = new LinkedHashMap<>();
        // table node id -> its schema name (for edge roll-up).
        String[] schemaOfTable = new String[tableNodes.size()];
        for (GraphModel.Node n : tableNodes) {
            String schema = n.schema() != null && !n.schema().isBlank()
                ? n.schema() : "(default)";
            schemaOfTable[n.id()] = schema;
            schemaId.computeIfAbsent(schema, k -> schemaId.size());
        }

        // Count table-count per schema and roll edges up between schemas.
        int schemaCount = schemaId.size();
        int[] tableCountPerSchema = new int[schemaCount];
        for (String schema : schemaOfTable) {
            tableCountPerSchema[schemaId.get(schema)]++;
        }

        // Inter-schema edge weights, keyed "srcSchemaId:dstSchemaId". Same-schema
        // (internal) relationships are not drawn as overview edges.
        Map<Long, Integer> edgeWeights = new LinkedHashMap<>();
        int[] outSchemas = new int[schemaCount];
        int[] inSchemas = new int[schemaCount];
        boolean[][] seenPair = new boolean[schemaCount][schemaCount];
        for (GraphModel.Edge e : tableGraph.getEdges()) {
            int s = schemaId.get(schemaOfTable[e.source()]);
            int t = schemaId.get(schemaOfTable[e.target()]);
            if (s == t) {
                continue; // internal relationship — not an overview edge
            }
            long key = ((long) s << 32) | (t & 0xffffffffL);
            edgeWeights.merge(key, 1, Integer::sum);
            if (!seenPair[s][t]) {
                seenPair[s][t] = true;
                outSchemas[s]++;
                inSchemas[t]++;
            }
        }

        // Emit super-nodes in dense-id order.
        String[] schemaNames = new String[schemaCount];
        for (Map.Entry<String, Integer> en : schemaId.entrySet()) {
            schemaNames[en.getValue()] = en.getKey();
        }
        for (int id = 0; id < schemaCount; id++) {
            String schema = schemaNames[id];
            String label = schema + " (" + tableCountPerSchema[id] + ")";
            overview.addNode(new GraphModel.Node(
                id, SCHEMA_PATH_PREFIX + schema, label, schema,
                inSchemas[id], outSchemas[id], 0));
        }

        // Emit aggregated inter-schema edges, weighted by the number of
        // underlying table relationships.
        for (Map.Entry<Long, Integer> en : edgeWeights.entrySet()) {
            long key = en.getKey();
            int s = (int) (key >> 32);
            int t = (int) (key & 0xffffffffL);
            overview.addEdge(new GraphModel.Edge(s, t, en.getValue(), GraphModel.KIND_FK));
        }

        return overview;
    }
}
