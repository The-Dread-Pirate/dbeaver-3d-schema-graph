/*
 * DBeaver 3D Schema Graph
 * Licensed under EPL-2.0.
 */
package org.jkiss.dbeaver.ext.graph3d.ui.model;

import org.jkiss.dbeaver.ext.graph3d.ui.GraphModel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, dependency-free transform from a {@link GraphSource} to a
 * {@link GraphModel}. Contains the only non-trivial logic in the plugin and is
 * exercised directly by unit tests with in-memory fakes.
 *
 * <p>Contract:
 * <ul>
 *   <li>Nodes are emitted in {@link GraphSource#entities()} order; {@code node.id}
 *       is the dense 0-based index, and edges reference nodes by that index.</li>
 *   <li>Nodes are keyed/deduplicated by {@code fullId}; a duplicate fullId in
 *       {@code entities()} keeps the first occurrence and is not re-added.</li>
 *   <li>A foreign key whose target fullId is not among the entities (a
 *       cross-container reference) is dropped from the edge list but still
 *       counted as an {@code outCount} on its source, and recorded as an
 *       {@code unresolvedCount} on the source -- so the degree a user sees on a
 *       node reflects every FK it declares, resolvable or not.</li>
 *   <li>A self-referential FK (source == target) produces a self-edge and
 *       increments both the source's out- and in-count.</li>
 *   <li>Multiple FKs between the same ordered pair produce multiple edges; the
 *       renderer collapses/weights them, and degree counts reflect every FK.</li>
 * </ul>
 */
public final class GraphBuilder {

    private GraphBuilder() {
    }

    public static GraphModel build(GraphSource source) {
        GraphModel model = new GraphModel();

        List<GraphSource.SourceEntity> entities = source.entities();

        // Pass 1: assign dense ids by fullId, preserving first-seen order.
        Map<String, Integer> idByFullId = new HashMap<>(entities.size() * 2);
        String[] fullIds = new String[entities.size()];
        String[] names = new String[entities.size()];
        int count = 0;
        for (GraphSource.SourceEntity e : entities) {
            if (idByFullId.containsKey(e.fullId())) {
                continue; // duplicate entity id -> keep first
            }
            int id = count++;
            idByFullId.put(e.fullId(), id);
            fullIds[id] = e.fullId();
            names[id] = e.name();
        }

        int[] inCount = new int[count];
        int[] outCount = new int[count];
        int[] unresolved = new int[count];

        // Pass 2: resolve FKs to edges and accumulate degree counts.
        for (int sourceId = 0; sourceId < count; sourceId++) {
            String sourceFullId = fullIds[sourceId];
            List<GraphSource.SourceAssociation> fks = source.associationsOf(sourceFullId);
            if (fks == null) {
                continue;
            }
            for (GraphSource.SourceAssociation fk : fks) {
                if (fk == null || fk.targetFullId() == null) {
                    continue;
                }
                outCount[sourceId]++;
                Integer targetId = idByFullId.get(fk.targetFullId());
                if (targetId == null) {
                    // FK points outside this container -> count but do not draw.
                    unresolved[sourceId]++;
                    continue;
                }
                model.addEdge(new GraphModel.Edge(sourceId, targetId, 1));
                inCount[targetId]++;
            }
        }

        // Emit nodes in dense-id order with resolved degree counts.
        for (int id = 0; id < count; id++) {
            model.addNode(new GraphModel.Node(
                id, fullIds[id], names[id],
                inCount[id], outCount[id], unresolved[id]));
        }

        return model;
    }
}
