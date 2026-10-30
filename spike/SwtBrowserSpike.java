package org.jkiss.dbeaver.ext.graph3d.spike;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.browser.ProgressAdapter;
import org.eclipse.swt.browser.ProgressEvent;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import java.io.File;
import java.nio.file.Paths;

/**
 * Phase-0 runtime de-risk spike.
 *
 * Loads {@code spike/probe.html} in a real SWT {@link Browser} and prints the
 * capability verdict (WebGL + blob Web Worker) that the probe reports back
 * through a {@link BrowserFunction}. This is the environment that actually
 * matters: the embedded browser backend, not a desktop Chrome.
 *
 * <p>Run it standalone (outside OSGi) against the platform SWT jar for your OS,
 * e.g. on Windows:
 * <pre>
 *   javac -cp "&lt;eclipse&gt;/plugins/org.eclipse.swt.win32.win32.x86_64_*.jar" ^
 *         -d out SwtBrowserSpike.java
 *   java  -cp "out;&lt;eclipse&gt;/plugins/org.eclipse.swt.win32.win32.x86_64_*.jar" ^
 *         org.jkiss.dbeaver.ext.graph3d.spike.SwtBrowserSpike path\to\spike\probe.html
 * </pre>
 *
 * <p>On Windows this forces the Edge/WebView2 (Chromium) backend via the
 * {@code SWT.EDGE} style; WebView2 Runtime must be installed (it ships with
 * current Windows 10/11). On Linux/macOS pass no style — SWT uses WebKitGTK /
 * WKWebView, which also support WebGL + blob workers.
 */
public final class SwtBrowserSpike {

    public static void main(String[] args) {
        String htmlPath = args.length > 0
            ? args[0]
            : Paths.get("spike", "probe.html").toAbsolutePath().toString();

        File html = new File(htmlPath);
        if (!html.isFile()) {
            System.err.println("[spike] probe.html not found at: " + html.getAbsolutePath());
            System.exit(2);
        }

        Display display = new Display();
        Shell shell = new Shell(display);
        shell.setText("DBeaver 3D Graph — Runtime Spike");
        shell.setLayout(new FillLayout());
        shell.setSize(720, 360);

        Browser browser = createBrowser(shell);
        System.out.println("[spike] SWT Browser backend = " + describeBackend(browser));

        // JS -> Java bridge: window.__spikeReport(jsonString)
        new BrowserFunction(browser, "__spikeReport") {
            @Override
            public Object function(Object[] arguments) {
                String json = arguments != null && arguments.length > 0
                    ? String.valueOf(arguments[0]) : "(empty)";
                System.out.println("[spike] VERDICT: " + json);
                return null;
            }
        };

        browser.addProgressListener(new ProgressAdapter() {
            @Override
            public void completed(ProgressEvent event) {
                System.out.println("[spike] page load completed");
            }
        });

        browser.setUrl(html.toURI().toString());

        shell.open();
        while (!shell.isDisposed()) {
            if (!display.readAndDispatch()) {
                display.sleep();
            }
        }
        display.dispose();
    }

    /**
     * Prefer the Chromium backend on Windows; fall back to the platform default
     * if the style is unavailable (mirrors GISLeafletViewer's defensive
     * construction against {@code ERROR_NOT_IMPLEMENTED}).
     */
    private static Browser createBrowser(Shell shell) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (windows) {
            try {
                return new Browser(shell, SWT.EDGE);
            } catch (SWTError edgeError) {
                System.out.println("[spike] SWT.EDGE unavailable (" + edgeError.getMessage()
                    + "); falling back to default backend");
            }
        }
        return new Browser(shell, SWT.NONE);
    }

    private static String describeBackend(Browser browser) {
        try {
            return String.valueOf(browser.getBrowserType());
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private SwtBrowserSpike() {}
}
