# Phase 0 — Runtime De-Risk Spike

Proves the two runtime unknowns before any plugin code is written:

1. **WebGL** — Pixi.js v8 needs a WebGL context; there is no usable Canvas
   fallback for the edge mesh, so this is a hard requirement.
2. **Blob-URL Web Worker** — the force-layout engine runs in a worker created
   from a `Blob` + `URL.createObjectURL`. If blocked, the reused code has a
   main-thread `isStatic` fallback.

## Files

- `probe.html` — in-browser capability tests; prints a GO / GO(fallback) /
  NO-GO verdict and, when embedded, calls `window.__spikeReport(json)`.
- `SwtBrowserSpike.java` — standalone SWT harness that loads `probe.html` in a
  real SWT `Browser` (the Edge/WebView2 backend on Windows) and prints the
  verdict to stdout.

## Quick baseline (any Chromium)

Open `probe.html` directly in Chrome/Edge. Since the SWT Edge backend *is*
WebView2/Chromium, a PASS here is a strong positive signal — but the SWT
harness is the authoritative test because it also checks blob-worker behavior
under the `file://`/embedded origin.

## Authoritative test (SWT Browser)

Locate the platform SWT jar in your Eclipse / DBeaver install, e.g.
`plugins/org.eclipse.swt.win32.win32.x86_64_<version>.jar`.

### Windows
```bat
javac -cp "<swt-jar>" -d out spike\SwtBrowserSpike.java
java  -cp "out;<swt-jar>" org.jkiss.dbeaver.ext.graph3d.spike.SwtBrowserSpike spike\probe.html
```
Windows forces `SWT.EDGE`; requires the WebView2 Runtime (ships with current
Windows 10/11).

### Linux / macOS
```bash
javac -cp "<swt-jar>" -d out spike/SwtBrowserSpike.java
# macOS also needs -XstartOnFirstThread
java -XstartOnFirstThread -cp "out:<swt-jar>" \
     org.jkiss.dbeaver.ext.graph3d.spike.SwtBrowserSpike spike/probe.html
```
Uses WebKitGTK (Linux) / WKWebView (macOS).

## Reading the result

stdout prints `[spike] VERDICT: {...}` with per-test `results` and a `verdict`:

- **GO** — WebGL 2 + blob worker: full fast path. Proceed to Phase 1.
- **GO (fallback)** — WebGL OK, no blob worker: proceed, wire the main-thread
  `isStatic` layout path.
- **NO-GO** — no WebGL: the embedding approach must be reconsidered (native
  Java/GEF 3D, or a different backend).
