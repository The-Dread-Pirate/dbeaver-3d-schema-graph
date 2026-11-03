/*
 * DBeaver 3D Schema Graph
 * Licensed under EPL-2.0.
 */
package org.jkiss.dbeaver.ext.graph3d.ui;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.dialogs.IInputValidator;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.browser.ProgressAdapter;
import org.eclipse.swt.browser.ProgressEvent;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.part.ViewPart;
import org.jkiss.dbeaver.ext.graph3d.ui.model.NavigationController;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;
import org.jkiss.dbeaver.ui.navigator.actions.NavigatorHandlerObjectOpen;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import java.net.URL;

/**
 * Standalone view hosting the 3D schema graph in an SWT {@link Browser}.
 *
 * <p>Loads the bundled web renderer ({@code web/index.html} + the vendored
 * Pixi.js/d3-force-3d bundle) and drives it:
 * <ul>
 *   <li>pushes a {@link GraphModel} into the page via the {@code __graph3d}
 *       JS host once the page has loaded;</li>
 *   <li>receives {@code navigateToObject(fullId)} and {@code requestDetails(fullId)}
 *       JS&rarr;Java callbacks as SWT {@link BrowserFunction}s.</li>
 * </ul>
 * The navigate sequencing lives in the pure {@link NavigationController}; this
 * class supplies only the DBeaver-backed resolver/opener and SWT thread
 * marshalling. Binding a live datasource to feed a real schema model is the
 * next phase; {@link #setModel} already accepts one.
 */
public class Graph3DView extends ViewPart {

    public static final String VIEW_ID = "org.jkiss.dbeaver.ext.graph3d.ui.Graph3DView";

    private Browser browser;

    /** Latest model JSON to push once the page finishes loading. */
    private volatile String pendingModelJson;
    /** Status line to show once the page is ready (if set before load). */
    private volatile String pendingStatus;
    private volatile boolean pageLoaded;

    /**
     * Project used to resolve a node's full id back to a {@link DBSObject}.
     * Set when a datasource/container is bound to the view; until then
     * navigation reports NOT_FOUND rather than failing hard.
     */
    private volatile DBPProject contextProject;

    /** The last container loaded, so changing the table cap can re-read it. */
    private volatile DBSObjectContainer lastContainer;

    @Override
    public void createPartControl(Composite parent) {
        browser = createBrowser(parent);
        contributeToolbar();

        NavigationController navigation = new NavigationController(
            this::resolveObject,
            resolved -> openEditorOnUi((DBSObject) resolved));

        // JS -> Java navigation bridge: window.navigateToObject(fullId)
        new BrowserFunction(browser, "navigateToObject") {
            @Override
            public Object function(Object[] arguments) {
                String fullId = firstString(arguments);
                NavigationController.Outcome outcome = navigation.navigate(fullId);
                if (!outcome.opened()) {
                    System.out.println("[graph3d] navigate: " + outcome.message());
                }
                return outcome.status().name();
            }
        };

        // JS -> Java details bridge: window.requestDetails(fullId) -> JSON string
        new BrowserFunction(browser, "requestDetails") {
            @Override
            public Object function(Object[] arguments) {
                String fullId = firstString(arguments);
                // Live attribute/FK extraction is wired with the datasource
                // binding (next phase). Returning null lets the JS side fall
                // back to the in-model node summary.
                System.out.println("[graph3d] requestDetails: " + fullId);
                return null;
            }
        };

        // Push any pending model once the renderer page has initialized.
        browser.addProgressListener(new ProgressAdapter() {
            @Override
            public void completed(ProgressEvent event) {
                pageLoaded = true;
                flushModel();
                // Surface a buffered status, or the idle message if nothing is
                // loaded yet (replaces the host page's initial "Loading…").
                String status = pendingStatus != null
                    ? pendingStatus
                    : (pendingModelJson == null
                        ? "No schema loaded — open the view from a database schema."
                        : null);
                if (status != null) {
                    setStatusNow(status);
                }
            }
        });

        String url = resolveWebUrl();
        if (url != null) {
            browser.setUrl(url);
        } else {
            browser.setText(loadErrorHtml());
        }
    }

    /**
     * Push a schema {@link GraphModel} into the view. Safe to call before the
     * page has loaded; the model is buffered and flushed on load completion.
     */
    public void setModel(GraphModel model) {
        this.pendingModelJson = model == null ? null : model.toJson();
        if (pageLoaded) {
            Display display = browser != null && !browser.isDisposed()
                ? browser.getDisplay() : Display.getDefault();
            display.asyncExec(this::flushModel);
        }
    }

    private void flushModel() {
        String json = pendingModelJson;
        if (json == null || browser == null || browser.isDisposed() || !pageLoaded) {
            return;
        }
        // Hand the JSON to the JS host; escape for a JS string literal.
        String script = "window.__graph3d && window.__graph3d.setModel("
            + jsStringLiteral(json) + ");";
        browser.execute(script);
    }

    /**
     * Load a schema/catalog into the view. Runs the <em>bounded</em> metadata
     * read on a background {@link AbstractJob} (off the UI thread, cancellable
     * via the Progress view or by closing the view), then pushes the resulting
     * {@link GraphModel} and a status line on the UI thread. Binds the
     * container's project so node navigation can resolve ids.
     *
     * <p>Safe for large schemas and databases: the extract is capped at the
     * user-configurable {@link Graph3DPreferences#getMaxTables()} limit and
     * reports when it truncated, which the status bar surfaces.
     */
    /**
     * Add a view-toolbar action to set the table cap. Prompts for an integer,
     * clamps + persists it via {@link Graph3DPreferences}, and re-reads the
     * currently loaded container so the new cap takes effect immediately.
     */
    private void contributeToolbar() {
        Action setCap = new Action("Set table limit…") {
            @Override
            public void run() {
                promptForTableCap();
            }
        };
        setCap.setToolTipText("Maximum number of tables to read into the graph");
        getViewSite().getActionBars().getToolBarManager().add(setCap);
    }

    private void promptForTableCap() {
        int current = Graph3DPreferences.getMaxTables();
        IInputValidator validator = input -> {
            final int min = Graph3DPreferences.MIN_MAX_TABLES;
            final int max = Graph3DPreferences.MAX_MAX_TABLES;
            if (input == null || input.isBlank()) {
                return "Enter a number";
            }
            final int n;
            try {
                n = Integer.parseInt(input.trim());
            } catch (NumberFormatException e) {
                return "Must be a whole number";
            }
            if (n < min || n > max) {
                return "Must be between " + min + " and " + max;
            }
            return null;
        };
        InputDialog dialog = new InputDialog(
            browser.getShell(),
            "3D Graph — table limit",
            "Maximum tables to read (" + Graph3DPreferences.MIN_MAX_TABLES
                + "–" + Graph3DPreferences.MAX_MAX_TABLES
                + "). Larger values read more catalog metadata and render more nodes.",
            String.valueOf(current),
            validator);
        if (dialog.open() != Window.OK) {
            return;
        }
        int next = Integer.parseInt(dialog.getValue().trim());
        Graph3DPreferences.setMaxTables(next);
        // Re-read the current graph with the new cap, if one is loaded.
        DBSObjectContainer container = this.lastContainer;
        if (container != null) {
            loadContainer(container);
        }
    }

    public void loadContainer(DBSObjectContainer container) {
        if (container == null) {
            return;
        }
        this.lastContainer = container;
        // Resolve the owning project for navigate round-trips.
        DBPProject project = DBUtils.getObjectOwnerProject(container);
        if (project != null) {
            this.contextProject = project;
        }
        setStatus("Reading schema…");

        AbstractJob job = new AbstractJob("Build 3D schema graph") {
            @Override
            protected IStatus run(DBRProgressMonitor monitor) {
                try {
                    int maxTables = Graph3DPreferences.getMaxTables();
                    SchemaMetadataExtractor.ExtractResult result =
                        SchemaMetadataExtractor.extract(monitor, container, maxTables);
                    if (result.cancelled()) {
                        uiStatus("Cancelled.");
                        return Status.CANCEL_STATUS;
                    }
                    final String status = result.truncated()
                        ? String.format(
                            "Showing first %d of %d tables (limit %d — raise via the toolbar) · %d relationships",
                            result.includedTables(), result.totalTables(), maxTables,
                            result.model().getEdges().size())
                        : String.format("%d tables · %d relationships",
                            result.includedTables(), result.model().getEdges().size());
                    pushModel(result.model(), status);
                    return Status.OK_STATUS;
                } catch (Exception e) {
                    uiStatus("Failed to read schema: " + e.getMessage());
                    return Status.CANCEL_STATUS;
                }
            }
        };
        job.schedule();
    }

    /** Push model + status onto the UI thread (from a background job). */
    private void pushModel(GraphModel model, String status) {
        Display display = browser != null && !browser.isDisposed()
            ? browser.getDisplay() : Display.getDefault();
        display.asyncExec(() -> {
            setModel(model);
            setStatusNow(status);
        });
    }

    private void uiStatus(String text) {
        Display display = browser != null && !browser.isDisposed()
            ? browser.getDisplay() : Display.getDefault();
        display.asyncExec(() -> setStatusNow(text));
    }

    /** Buffer a status line to show when the page is ready. */
    private void setStatus(String text) {
        if (pageLoaded) {
            setStatusNow(text);
        } else {
            pendingStatus = text;
        }
    }

    /** Set the status bar text now (UI thread). */
    private void setStatusNow(String text) {
        if (browser == null || browser.isDisposed() || !pageLoaded) {
            pendingStatus = text;
            return;
        }
        browser.execute(
            "var s=document.getElementById('graph3d-status'); if(s) s.textContent="
                + jsStringLiteral(text) + ";");
    }

    /** Resolve a node full id to its {@link DBSObject} (off the UI thread). */
    private Object resolveObject(String fullId) throws Exception {
        DBPProject project = contextProject;
        if (project == null) {
            return null;
        }
        return DBUtils.findObjectById(new VoidProgressMonitor(), project, fullId);
    }

    /** Open the object's editor on the SWT UI thread. */
    private void openEditorOnUi(DBSObject object) {
        Display display = browser != null && !browser.isDisposed()
            ? browser.getDisplay() : Display.getDefault();
        display.syncExec(() -> NavigatorHandlerObjectOpen.openEntityEditor(object));
    }

    /** Locate the bundled web/index.html as a browser-loadable file URL. */
    private static String resolveWebUrl() {
        try {
            Bundle bundle = FrameworkUtil.getBundle(Graph3DView.class);
            if (bundle == null) {
                return null;
            }
            // Resolve the web/ DIRECTORY first, not just index.html. FileLocator
            // unpacks what it is handed: asking only for web/index.html extracts
            // that single file and leaves its sibling graph3d.bundle.js behind,
            // so the page's <script src="graph3d.bundle.js"> then 404s
            // (ERR_FILE_NOT_FOUND). Unpacking the directory lands the whole
            // web/ subtree — index.html AND the bundle — into the cache together.
            URL dirEntry = bundle.getEntry("web/");
            if (dirEntry == null) {
                return null;
            }
            URL unpackedDir = FileLocator.toFileURL(dirEntry);
            // index.html sits directly inside the unpacked directory. No query
            // string: a "?v=" suffix on a file:// URL perturbs how the browser
            // resolves the relative <script> path. The Bundle-Version bump is
            // what defeats the stale-unpack cache (a new version => new cache dir).
            String dir = unpackedDir.toExternalForm();
            if (!dir.endsWith("/")) {
                dir = dir + "/";
            }
            return dir + "index.html";
        } catch (Exception e) {
            System.out.println("[graph3d] failed to resolve web/index.html: " + e.getMessage());
            return null;
        }
    }

    private static String firstString(Object[] arguments) {
        return arguments != null && arguments.length > 0 && arguments[0] != null
            ? String.valueOf(arguments[0]) : null;
    }

    /** Escape a string as a double-quoted JS string literal. */
    private static String jsStringLiteral(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '<'  -> b.append("\\x3c"); // avoid </script> breakout
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
        return b.toString();
    }

    private static Browser createBrowser(Composite parent) {
        // Prefer the Edge/WebView2 (Chromium) backend on Windows for WebGL +
        // modern Web Workers; fall back if it is unavailable at runtime.
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            try {
                enableEdgeDevTools();
                return new Browser(parent, SWT.EDGE);
            } catch (Error edgeUnavailable) {
                // Fall through to the default backend.
            }
        }
        return new Browser(parent, SWT.NONE);
    }

    /**
     * Turn on WebView2 developer tools + a CDP remote-debugging port so the
     * embedded renderer's console/errors are inspectable.
     *
     * <p>SWT's Edge backend forwards the system property
     * {@code org.eclipse.swt.browser.EdgeArgs} verbatim to WebView2's
     * {@code --additional-browser-arguments}. These must be set BEFORE the
     * first {@link Browser} with {@link SWT#EDGE} is created, because the
     * WebView2 environment is initialised once per process and then cached.
     *
     * <ul>
     *   <li>{@code --auto-open-devtools-for-tabs} opens the DevTools pane
     *       automatically beside the canvas on first load;</li>
     *   <li>{@code --remote-debugging-port=9333} exposes the Chrome DevTools
     *       Protocol on localhost so the page can also be inspected from an
     *       external Chromium/CDP client.</li>
     * </ul>
     *
     * Right-click &rarr; <em>Inspect</em> is always available once DevTools is
     * enabled; the auto-open flag just surfaces it without the extra click.
     */
    private static void enableEdgeDevTools() {
        final String key = "org.eclipse.swt.browser.EdgeArgs";
        String existing = System.getProperty(key, "");
        String devtoolsArgs =
            "--auto-open-devtools-for-tabs --remote-debugging-port=9333";
        if (!existing.contains("--remote-debugging-port")) {
            String merged = existing.isBlank()
                ? devtoolsArgs
                : existing + " " + devtoolsArgs;
            System.setProperty(key, merged);
        }
        // Explicitly allow DevTools in case a host policy disabled it.
        System.setProperty("org.eclipse.swt.browser.EdgeDevToolsEnabled", "true");
    }

    private static String loadErrorHtml() {
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>"
            + "<style>body{font:14px system-ui;padding:1rem;}</style></head>"
            + "<body><h3>DBeaver 3D Schema Graph</h3>"
            + "<p>Could not locate the bundled renderer (web/index.html).</p>"
            + "</body></html>";
    }

    /** Bind the project whose objects this view's node ids resolve against. */
    public void setContextProject(DBPProject project) {
        this.contextProject = project;
    }

    @Override
    public void setFocus() {
        if (browser != null && !browser.isDisposed()) {
            browser.setFocus();
        }
    }

    @Override
    public void dispose() {
        if (browser != null && !browser.isDisposed()) {
            browser.dispose();
        }
        super.dispose();
    }
}
