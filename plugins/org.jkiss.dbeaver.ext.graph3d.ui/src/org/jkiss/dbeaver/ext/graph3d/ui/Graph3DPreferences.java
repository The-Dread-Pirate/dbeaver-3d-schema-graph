/*
 * DBeaver 3D Schema Graph
 * Licensed under EPL-2.0.
 */
package org.jkiss.dbeaver.ext.graph3d.ui;

import java.util.prefs.Preferences;

/**
 * User-adjustable settings for the 3D graph, persisted per user via the
 * standard {@link java.util.prefs.Preferences} store (no extra OSGi bundle
 * dependency, available on every JVM). Values survive restarts and are shared
 * across all workspaces for this user.
 *
 * <p>Currently just the table cap — how many tables an extract will read before
 * truncating. It's a safety bound against pulling an unbounded amount of
 * catalog metadata from a large database, so it is clamped to a sane range
 * rather than trusted blindly.
 */
public final class Graph3DPreferences {

    /** Preferences node key; namespaced to this plugin. */
    private static final String NODE = "org/jkiss/dbeaver/ext/graph3d/ui";
    private static final String KEY_MAX_TABLES = "maxTables";

    /** Default table cap. Raised from the original 500 to comfortably cover a
     *  mid-size database across its schemas while staying a real bound. */
    public static final int DEFAULT_MAX_TABLES = 2000;

    /** Hard floor so a bad value can't disable the graph entirely. */
    public static final int MIN_MAX_TABLES = 10;
    /** Hard ceiling: beyond this the metadata read and the render stop being
     *  reasonable, so we refuse to go higher even if asked. */
    public static final int MAX_MAX_TABLES = 20000;

    private Graph3DPreferences() {
    }

    private static Preferences node() {
        return Preferences.userRoot().node(NODE);
    }

    /**
     * The configured table cap, clamped to {@link #MIN_MAX_TABLES}..
     * {@link #MAX_MAX_TABLES}. Falls back to {@link #DEFAULT_MAX_TABLES} when
     * unset or if the preferences store is unavailable.
     */
    public static int getMaxTables() {
        int value;
        try {
            value = node().getInt(KEY_MAX_TABLES, DEFAULT_MAX_TABLES);
        } catch (Exception e) {
            // Preferences backing store unavailable — use the default.
            return DEFAULT_MAX_TABLES;
        }
        return clamp(value);
    }

    /**
     * Persist a new table cap (clamped to the allowed range). Best-effort: a
     * store failure is swallowed, since a missing setting just means the
     * default is used next time.
     */
    public static void setMaxTables(int value) {
        try {
            node().putInt(KEY_MAX_TABLES, clamp(value));
            node().flush();
        } catch (Exception e) {
            // Best-effort persistence; ignore a backing-store failure.
        }
    }

    private static int clamp(int value) {
        if (value < MIN_MAX_TABLES) {
            return MIN_MAX_TABLES;
        }
        if (value > MAX_MAX_TABLES) {
            return MAX_MAX_TABLES;
        }
        return value;
    }
}
