/*
 * DBeaver 3D Schema Graph
 * Licensed under EPL-2.0.
 */
package org.jkiss.dbeaver.ext.graph3d.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;

/**
 * Handler for the "Open in 3D Schema Graph" navigator command. Reads the
 * selected Database Navigator node, resolves it to a {@link DBSObjectContainer}
 * (a schema/catalog/database that holds entities), opens the {@link Graph3DView}
 * and asks it to load that container's tables + foreign keys.
 *
 * <p>The handler validates the selection itself rather than relying on a
 * declarative {@code visibleWhen} expression, so the command is robust across
 * node types: a non-container selection is a no-op (nothing to graph).
 */
public class OpenGraph3DHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        DBSObjectContainer container = resolveContainer(HandlerUtil.getCurrentSelection(event));
        if (container == null) {
            return null; // selection is not a container; nothing to graph
        }

        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        if (window == null) {
            return null;
        }
        IWorkbenchPage page = window.getActivePage();
        if (page == null) {
            return null;
        }
        try {
            // Reveal (or create) the view, then hand it the container.
            Graph3DView view = (Graph3DView) page.showView(Graph3DView.VIEW_ID);
            view.loadContainer(container);
        } catch (Exception e) {
            throw new ExecutionException("Failed to open 3D Schema Graph", e);
        }
        return null;
    }

    /**
     * Resolve a structured selection's first element to a
     * {@link DBSObjectContainer}, if it is a database navigator node wrapping
     * one (schema, catalog, or any entity container).
     */
    private static DBSObjectContainer resolveContainer(Object selection) {
        if (!(selection instanceof IStructuredSelection structured) || structured.isEmpty()) {
            return null;
        }
        Object first = structured.getFirstElement();
        if (first instanceof DBNDatabaseNode dbNode) {
            DBSObject object = dbNode.getObject();
            if (object instanceof DBSObjectContainer container) {
                return container;
            }
        } else if (first instanceof DBNNode) {
            // Non-database navigator node (project, folder) — nothing to graph.
            return null;
        }
        return null;
    }
}
