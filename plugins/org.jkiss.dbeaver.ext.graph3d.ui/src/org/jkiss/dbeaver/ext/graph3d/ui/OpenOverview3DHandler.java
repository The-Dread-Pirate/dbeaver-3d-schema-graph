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
 * Handler for the "Open in 3D Database Overview" navigator command. Same
 * selection resolution as {@link OpenGraph3DHandler}, but opens the view in
 * <em>overview</em> (drill-down) mode: one super-node per schema, which the
 * user can click to expand into that schema's full table graph.
 */
public class OpenOverview3DHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        DBSObjectContainer container = resolveContainer(HandlerUtil.getCurrentSelection(event));
        if (container == null) {
            return null;
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
            Graph3DView view = (Graph3DView) page.showView(Graph3DView.VIEW_ID);
            view.loadOverview(container);
        } catch (Exception e) {
            throw new ExecutionException("Failed to open 3D Database Overview", e);
        }
        return null;
    }

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
            return null;
        }
        return null;
    }
}
