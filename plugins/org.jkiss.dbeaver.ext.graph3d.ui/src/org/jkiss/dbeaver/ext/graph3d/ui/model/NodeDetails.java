/*
 * DBeaver 3D Schema Graph
 * Licensed under EPL-2.0.
 */
package org.jkiss.dbeaver.ext.graph3d.ui.model;

import java.util.List;

/**
 * Detail payload for a single selected node, shown in the object-details panel.
 * Pure data + dependency-free JSON, mirroring {@link GraphModel}'s approach so
 * the bundled JS consumes it unchanged:
 *
 * <pre>
 *   { "fullId": "...", "name": "orders", "type": "TABLE",
 *     "attributes": [ { "name": "id", "type": "INTEGER", "nullable": false,
 *                       "keyType": "PRIMARY" }, ... ],
 *     "foreignKeys": [ { "name": "fk_customer", "refTable": "customers",
 *                        "columns": "customer_id -> id" }, ... ] }
 * </pre>
 *
 * Built by the adapter from DBeaver metadata; also directly constructible in
 * tests. The {@code toJson} escaping matches {@link GraphModel#toJson()}.
 */
public final class NodeDetails {

    /** A column of the selected entity. */
    public record Attribute(String name, String type, boolean nullable, String keyType) {
    }

    /** A foreign key the selected entity declares. */
    public record ForeignKey(String name, String refTable, String columns) {
    }

    private final String fullId;
    private final String name;
    private final String type;
    private final List<Attribute> attributes;
    private final List<ForeignKey> foreignKeys;

    public NodeDetails(String fullId, String name, String type,
                       List<Attribute> attributes, List<ForeignKey> foreignKeys) {
        this.fullId = fullId;
        this.name = name;
        this.type = type;
        this.attributes = attributes == null ? List.of() : List.copyOf(attributes);
        this.foreignKeys = foreignKeys == null ? List.of() : List.copyOf(foreignKeys);
    }

    public String fullId() {
        return fullId;
    }

    public String name() {
        return name;
    }

    public String type() {
        return type;
    }

    public List<Attribute> attributes() {
        return attributes;
    }

    public List<ForeignKey> foreignKeys() {
        return foreignKeys;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder(128);
        sb.append('{')
          .append("\"fullId\":").append(Json.quote(fullId))
          .append(",\"name\":").append(Json.quote(name))
          .append(",\"type\":").append(Json.quote(type))
          .append(",\"attributes\":[");
        for (int i = 0; i < attributes.size(); i++) {
            Attribute a = attributes.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{')
              .append("\"name\":").append(Json.quote(a.name()))
              .append(",\"type\":").append(Json.quote(a.type()))
              .append(",\"nullable\":").append(a.nullable())
              .append(",\"keyType\":").append(Json.quote(a.keyType()))
              .append('}');
        }
        sb.append("],\"foreignKeys\":[");
        for (int i = 0; i < foreignKeys.size(); i++) {
            ForeignKey fk = foreignKeys.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{')
              .append("\"name\":").append(Json.quote(fk.name()))
              .append(",\"refTable\":").append(Json.quote(fk.refTable()))
              .append(",\"columns\":").append(Json.quote(fk.columns()))
              .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }
}
