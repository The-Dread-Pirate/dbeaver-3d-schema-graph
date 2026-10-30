/*
 * DBeaver 3D Schema Graph
 * Licensed under EPL-2.0.
 */
package org.jkiss.dbeaver.ext.graph3d.ui.model;

/**
 * Pure, provider-agnostic sequencing of the navigate-to-object flow:
 * validate the full id -&gt; {@link ObjectResolver resolve} it -&gt;
 * {@link EditorOpener open} the editor, reporting a structured {@link Outcome}.
 *
 * <p>The two SPIs are the only seam to DBeaver. {@code Graph3DView} wires the
 * real implementations ({@code DBUtils.findObjectById} and
 * {@code NavigatorHandlerObjectOpen.openEntityEditor}); tests supply fakes.
 * Keeping the control flow here -- rather than inline in the
 * {@code BrowserFunction} -- means the "what happens when the id is blank /
 * unknown / resolve throws" logic is unit-tested, while the view carries only
 * the thread-marshalling glue.
 */
public final class NavigationController {

    /** What a single navigation request resulted in. */
    public enum Status {
        /** Editor opened. */
        OPENED,
        /** The incoming id was null/blank. */
        INVALID_ID,
        /** The id did not resolve to any database object. */
        NOT_FOUND,
        /** Resolution or opening failed with an error. */
        ERROR
    }

    /** Structured result, including a human-readable message for logging/UI. */
    public record Outcome(Status status, String message) {
        public boolean opened() {
            return status == Status.OPENED;
        }
    }

    /** Resolves a stable full id to an opaque object handle, or null if absent. */
    @FunctionalInterface
    public interface ObjectResolver {
        Object resolve(String fullId) throws Exception;
    }

    /** Opens the editor for a previously resolved object handle. */
    @FunctionalInterface
    public interface EditorOpener {
        void open(Object resolved) throws Exception;
    }

    private final ObjectResolver resolver;
    private final EditorOpener opener;

    public NavigationController(ObjectResolver resolver, EditorOpener opener) {
        this.resolver = resolver;
        this.opener = opener;
    }

    /**
     * Run the flow for one requested full id. Never throws: every failure path
     * is captured in the returned {@link Outcome}.
     */
    public Outcome navigate(String fullId) {
        if (fullId == null || fullId.isBlank()) {
            return new Outcome(Status.INVALID_ID, "navigateToObject called with a blank id");
        }
        Object resolved;
        try {
            resolved = resolver.resolve(fullId);
        } catch (Exception e) {
            return new Outcome(Status.ERROR, "failed to resolve '" + fullId + "': " + e.getMessage());
        }
        if (resolved == null) {
            return new Outcome(Status.NOT_FOUND, "no database object for id '" + fullId + "'");
        }
        try {
            opener.open(resolved);
        } catch (Exception e) {
            return new Outcome(Status.ERROR, "failed to open '" + fullId + "': " + e.getMessage());
        }
        return new Outcome(Status.OPENED, "opened '" + fullId + "'");
    }
}
