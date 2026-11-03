/*
 * DBeaver 3D Schema Graph
 * Licensed under EPL-2.0.
 */
package org.jkiss.dbeaver.ext.graph3d.ui;

import org.jkiss.dbeaver.ext.graph3d.ui.model.Json;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory graph model handed to the JS viz layer. Field names mirror the
 * reused Obsidian renderer's {@code GraphModel} contract so the bundled
 * JavaScript consumes it unchanged:
 *
 * <pre>
 *   node: { id, path, name, inCount, outCount, unresolvedCount }
 *   edge: { source, target, weight }
 * </pre>
 *
 * {@code node.id} is the dense array index; edges reference nodes by index.
 * For DBeaver, {@code path} is the fully-qualified object id (used to resolve
 * back to a {@code DBSObject} for navigation) and {@code name} is the display
 * name.
 */
public final class GraphModel {

    /** A table/entity node. {@code schema} is the name of the containing
     *  schema/catalog (empty when unknown or not applicable), surfaced so the
     *  UI can show which schema an object and its references live in. */
    public record Node(int id, String path, String name, String schema,
                       int inCount, int outCount, int unresolvedCount) {

        /** Backward-compatible constructor defaulting schema to empty. */
        public Node(int id, String path, String name,
                    int inCount, int outCount, int unresolvedCount) {
            this(id, path, name, "", inCount, outCount, unresolvedCount);
        }
    }

    /**
     * A relationship between two nodes, by node index. {@code kind}
     * distinguishes the relationship type: {@link #KIND_FK} for a foreign key,
     * {@link #KIND_VIEW} for a view referencing a table in its definition.
     */
    public record Edge(int source, int target, int weight, String kind) {

        /** Convenience for a foreign-key edge (the default kind). */
        public Edge(int source, int target, int weight) {
            this(source, target, weight, KIND_FK);
        }
    }

    /** Edge kind: a foreign-key relationship. */
    public static final String KIND_FK = "fk";
    /** Edge kind: a view -> referenced-table relationship. */
    public static final String KIND_VIEW = "view";

    private final List<Node> nodes = new ArrayList<>();
    private final List<Edge> edges = new ArrayList<>();

    public List<Node> getNodes() {
        return nodes;
    }

    public List<Edge> getEdges() {
        return edges;
    }

    public void addNode(Node node) {
        nodes.add(node);
    }

    public void addEdge(Edge edge) {
        edges.add(edge);
    }

    /**
     * Minimal, dependency-free JSON serialization. Avoids pulling in a JSON
     * bundle for a shape this small and fixed. Strings are escaped for the
     * characters that are illegal in a JSON string literal.
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder(256 + nodes.size() * 64 + edges.size() * 32);
        sb.append("{\"nodes\":[");
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":").append(n.id())
              .append(",\"path\":").append(Json.quote(n.path()))
              .append(",\"name\":").append(Json.quote(n.name()))
              .append(",\"schema\":").append(Json.quote(n.schema()))
              .append(",\"inCount\":").append(n.inCount())
              .append(",\"outCount\":").append(n.outCount())
              .append(",\"unresolvedCount\":").append(n.unresolvedCount())
              .append('}');
        }
        sb.append("],\"edges\":[");
        for (int i = 0; i < edges.size(); i++) {
            Edge e = edges.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"source\":").append(e.source())
              .append(",\"target\":").append(e.target())
              .append(",\"weight\":").append(e.weight())
              .append(",\"kind\":").append(Json.quote(e.kind()))
              .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }
}
