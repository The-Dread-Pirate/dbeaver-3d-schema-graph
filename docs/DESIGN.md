# DBeaver 3D Schema Graph — Design

## 1. Goal

A separately-installable DBeaver CE plugin that renders a database schema as an
interactive 3D graph:

- tables → 3D nodes, foreign keys → edges;
- zoom, rotate, drag, select nodes;
- show object details on selection;
- navigate to the corresponding object in DBeaver's Database Navigator.

Built on DBeaver's Eclipse RCP/OSGi extension architecture **without modifying
core**, reusing the Obsidian `advanced_graph_view` rendering engine inside an
SWT `Browser` widget.

## 2. Reuse analysis (summary)

Two stacks: the Obsidian plugin is TypeScript/Pixi.js in Chromium; DBeaver is
Java/SWT on Eclipse RCP. "Reuse" = run the JS rendering core verbatim inside an
SWT `Browser`, with a Java glue layer for metadata and navigation.

The viz layer renders with **Pixi.js v8 (WebGL) + a CPU pseudo-3D projection**
(`Camera3D.project`: yaw/pitch rotation → perspective divide → flat sprites)
over a **d3-force-3d** layout. Not Three.js — lighter, no 3D scene graph.

Reuse estimate (viz layer):

- **~70% verbatim (zero Obsidian deps):** `render/**` (`GraphRenderer`,
  `projection`, `EdgeMesh`, `hitTest`, `dragMath`, `Viewport`,
  `clickDispatcher`), `data/GraphStore`, `workers/**` (layout + metrics),
  `explore/**`, `analysis/**`, `encoding/**`, `query/**`.
- **~10% thin shim:** `GraphRenderer.ts` (replace one `createDiv()` call with
  `document.createElement`); UI widgets (`ControlPanel`, `ToolBar`,
  `CameraWidget`, …) need a `setIcon`/`createDiv` polyfill.
- **~20% rewrite:** `main.ts` (Obsidian lifecycle), `view/GraphView.ts` (the
  vault↔model wiring + navigation), `persistence.ts`, settings/modal dialogs.

### The two seams

The core talks to its host through exactly two points:

1. **Data in** (`GraphView.ts` ~1724): a pure function
   `buildGraphModel(paths: string[], resolvedLinks: LinkMap, unresolvedLinks: LinkMap)`
   → `renderer.setModel(model)`. Model shape:
   ```ts
   interface GraphNode { id: number; path: string; name: string;
                         inCount: number; outCount: number; unresolvedCount: number; }
   interface GraphEdge { source: number; target: number; weight: number; }
   interface GraphModel { nodes: GraphNode[]; edges: GraphEdge[];
                          pathToId: Map<string, number>; }
   type LinkMap = Record<string, Record<string, number>>;
   ```
   `node.id` is the dense array index; edges reference nodes by index.

2. **Navigate out** (`GraphView.ts` ~1592): a single `openNode()` method that
   calls `workspace.getLeaf().openFile(file)`. Replace this one method.

The renderer surfaces interaction via `RendererCallbacks`
(`GraphRenderer.ts:77`): `onNodeHover`, `onNodeClick`, `onNodeDoubleClick`,
`onNodeContextMenu`, `onNodeDrag*`, `onLassoSelect`, etc. Zoom/rotate/drag/
hit-test are already implemented in the pure layer — no new work.

## 3. DBeaver integration (no core modification)

All APIs are in exported OSGi bundles. The **ER Diagram editor is the closest
prior art** — it already renders tables + FKs as a 2D diagram.

### Metadata (bundle `org.jkiss.dbeaver.model`)
- `DBSObjectContainer` — schema/container; `cacheStructure(monitor, STRUCT_ENTITIES
  | STRUCT_ATTRIBUTES | STRUCT_ASSOCIATIONS)` then `getChildren(monitor)`.
- `DBSEntity` (table) — `getAttributes()` (columns), `getAssociations()`
  (outgoing FKs), `getReferences()` (incoming FKs).
- `DBSEntityAssociation.getAssociatedEntity()` → referenced table;
  `DBSTableForeignKey` adds delete/update rules.
- Lazy loading via `DBRProgressMonitor` (`VoidProgressMonitor` off the UI thread).
- Extractor to mirror: `ERDUtils.collectDatabaseTables(...)` in
  `org.jkiss.dbeaver.model.erd`.

### Embedding (proven in-tree)
- `GISLeafletViewer` (`org.jkiss.dbeaver.data.gis.view`) wires
  `new Browser(composite, SWT.NONE)` + `BrowserFunction` (JS→Java) and
  `browser.execute(...)` (Java→JS). Fallback to `SWT.WEBKIT` on
  `ERROR_NOT_IMPLEMENTED`.
- On Windows, force the **Edge/WebView2 (Chromium)** backend; legacy IE/Trident
  will not run Pixi 8 or modern workers.

### Navigate-to-object (bundle `org.jkiss.dbeaver.ui.navigator`)
- `NavigatorHandlerObjectOpen.openEntityEditor(DBSObject)` opens the object's
  metadata editor.
- `NavigatorUtils.openNavigatorNode(node, window, params)` reveals it in the
  Database Navigator tree.

### UI contribution
- Extension point `org.jkiss.dbeaver.databaseEditor` attaches the view as an
  object-editor tab on `DBSObjectContainer` / `DBSTable` (copy the ERD
  `plugin.xml`). Optionally `org.eclipse.ui.views` for a standalone panel.

## 4. Proposed bundle skeleton

Bundle `org.jkiss.dbeaver.ext.graph3d.ui;singleton:=true`,
`Bundle-RequiredExecutionEnvironment: JavaSE-21`.

```
Require-Bundle: org.jkiss.dbeaver.model;visibility:=reexport,
 org.jkiss.dbeaver.model.rcp,
 org.jkiss.dbeaver.ui,
 org.jkiss.dbeaver.ui.navigator,
 org.jkiss.dbeaver.ui.editors.base,
 org.jkiss.dbeaver.registry,
 org.eclipse.core.expressions
```
(SWT/`Browser` arrive transitively via `org.jkiss.dbeaver.ui`.)

Java classes:
1. `Graph3DEditorPart` — SWT `Browser` host (mirror `ERDEditorEmbedded`).
2. `SchemaMetadataExtractor` — DBeaver metadata → `GraphModel` JSON (mirror
   `ERDUtils.collectDatabaseTables`): tables become node "paths", FKs become
   weighted links in the `resolvedLinks` map.
3. `BrowserHost` — loads the bundled Pixi build, pushes JSON via
   `browser.execute`, registers bridge functions.
4. `NavigateBridgeFunction extends BrowserFunction` — JS
   `window.navigateToObject(id)` → resolve `DBSObject` →
   `NavigatorHandlerObjectOpen.openEntityEditor(obj)`.

## 5. Runtime risk (the one real unknown)

The code is ready; the risk is **environmental**: does the SWT `Browser`
backend expose a working **WebGL** context and permit **blob-URL Web Workers**
on each target OS (Edge/WebView2 on Windows; WebKitGTK/WKWebView on Linux/macOS)?

Mitigations already in the reused code:
- `GraphRenderer.init` prefers WebGL; there is no usable Canvas fallback for
  `EdgeMesh`, so WebGL is a hard requirement.
- The layout engine has a main-thread `isStatic` tick path, so a worker-less
  fallback exists if blob workers are blocked.

**Phase 0 (this spike) resolves this before any adapter code is written.** See
`spike/`.

### Phase 0 result — RESOLVED: GO (verified 2026-10-29, user's Windows desktop)

The spike ran in a real SWT `Browser` on the user's workstation against their
DBeaver install (`org.eclipse.swt.win32.win32.x86_64_3.135.0`, native DLLs from
the DBeaver root via `-Djava.library.path`). Authoritative verdict:

- **Backend = `edge`** — SWT loaded the Edge/WebView2 (Chromium) backend, the
  one DBeaver uses on Windows.
- **WebGL 2 = PASS** — context created; renderer `ANGLE (Microsoft Basic Render
  Driver) Direct3D11 vs_5_0 ps_5_0` (software D3D11 — this box has no GPU; a
  workstation with a GPU gets hardware acceleration).
- **WebGL 1 = PASS.**
- **Blob-URL Web Worker = PASS** — round-trip succeeded under the embedded
  `file://` origin (the one thing a desktop-Chrome baseline could not prove).

> `VERDICT: GO — full fast path available (WebGL 2 + blob worker).`

**Decision: take the full fast path** — Pixi.js v8 WebGL renderer + `d3-force-3d`
worker layout, exactly as the Obsidian plugin ships. No `isStatic` fallback
needed. The `--enable-native-access` warnings are benign JDK 21 native-access
notices, not errors. One-click runner: `spike/run.bat`.

## 6. Phased plan

- **Phase 0 — runtime spike** ✅ DONE (GO): WebGL 2 + blob Worker both PASS in
  the SWT Edge backend. See §5 "Phase 0 result".
- **Phase 1 — bundle skeleton**: MANIFEST/`plugin.xml`/4 classes, "hello graph"
  with a hardcoded 3-node model.
- **Phase 2 — metadata adapter** ✅ DONE (verified headless): pure
  `GraphSource`→`GraphBuilder`→`GraphModel` core with 12 passing JUnit tests;
  `SchemaMetadataExtractor` reduced to a thin DBeaver→`GraphSource` adapter.
  See §8 "Phase 2 result".
- **Phase 3 — navigation + details** ✅ DONE (compiler-verified + unit-tested):
  pure `NavigationController` (validate→resolve→open→Outcome) + `NodeDetails`
  payload + shared `Json`; `Graph3DView` wires the real DBeaver resolver/opener
  and thread marshalling. See §9 "Phase 3 result".
- **Phase 4 — viz integration**: wire the reused Pixi build; shims for
  `setIcon`/`createDiv`.
- **Phase 4 — viz integration** ✅ DONE (built + bundled, runtime render pending
  on a desktop): vendored the MIT Pixi.js/d3-force-3d renderer, applied the
  `createDiv` shim, authored a self-contained browser entry + main-thread force
  layout, esbuild-bundled it into the plugin's `web/`, and wired `Graph3DView`
  to load it and push a `GraphModel`. See §10 "Phase 4 result".
- **Phase 5 — packaging** ✅ DONE (built + verified): `eclipse-feature` +
  `eclipse-repository` modules produce an installable p2 update site (and a
  219 KB zip). See §11 "Phase 5 result".

## 7. Build environment (verified 2026-10-29)

Durable toolchain lives at `C:\Users\sa.tw\workplace\kirocrew-workspace\toolchain`
(dot-source `env.ps1` to put it on PATH):

- **JDK 21.0.5** (Temurin), **Maven 3.9.9**, **Tycho 4.0.8**.

The build is **self-contained** — it does NOT use DBeaver's reactor or its
private parent pom (`com.dbeaver.common.main`, in the separate `dbeaver-common`
repo). The root `pom.xml` defines a Tycho target platform resolving against two
public p2 repos:

- Eclipse `2024-09` (`https://download.eclipse.org/releases/2024-09/`) — SWT,
  JFace, UI, core.
- DBeaver CE latest (`https://dbeaver.io/update/ce/latest/`) —
  `org.jkiss.dbeaver.model.*`, `org.jkiss.dbeaver.ui*`.

Build: `mvn -B clean verify` from the repo root. First run resolves+caches the
target platform (~4 min); subsequent runs ~26s.

**Status: BUILD SUCCESS.** Produces the installable OSGi bundle
`plugins/org.jkiss.dbeaver.ext.graph3d.ui/target/org.jkiss.dbeaver.ext.graph3d.ui-0.1.0-SNAPSHOT.jar`.
The compiler confirms the design's load-bearing API calls resolve against the
real DBeaver + Eclipse platform: `DBSObjectContainer`, `DBSEntity.getAssociations`,
`DBSEntityAssociation.getAssociatedEntity`, `DBUtils.getObjectFullId`,
`org.eclipse.swt.browser.Browser` + `BrowserFunction`, `ViewPart`.

### Current source (Phase 0/1/2/3)
- `GraphModel.java` — node/edge records + JSON (now via shared `model/Json`)
  matching the reused viz contract.
- `model/Json.java` — shared dependency-free JSON-string escaping, used by
  `GraphModel` and `NodeDetails` (one tested routine, no duplication).
- `model/GraphSource.java` — **provider-agnostic SPI**: the single seam between
  DBeaver metadata and the pure builder (`SourceEntity{fullId,name}`,
  `SourceAssociation{sourceFullId,targetFullId}`). DBeaver's adapter implements
  it; tests fake it.
- `model/GraphBuilder.java` — **the pure algorithm**: `GraphSource` →
  `GraphModel`. Full-id keying (no entity-identity assumption), stable
  first-seen node ordering, self-reference support, cross-container FK counted
  as `unresolvedCount` but not drawn, parallel FKs preserved, defensive
  null-target skip. Fully unit-tested.
- `model/NodeDetails.java` — **pure object-details payload** (attributes + FKs)
  with its own JSON. Shown in the details panel on node select.
- `model/NavigationController.java` — **pure navigate flow**: validate →
  `ObjectResolver` → `EditorOpener` → structured `Outcome`
  (OPENED/INVALID_ID/NOT_FOUND/ERROR). Never throws; every branch tested.
- `SchemaMetadataExtractor.java` — thin DBeaver→`GraphSource` adapter (mirrors
  `ERDUtils.collectDatabaseTables`), then delegates to `GraphBuilder`. No
  untested branching.
- `Graph3DView.java` — `ViewPart` hosting an SWT `Browser` (Edge backend on
  Windows). Wires the `navigateToObject` + `requestDetails` JS→Java bridges to
  the pure controller, supplying DBeaver-backed `findObjectById` resolution
  (off the UI thread via `VoidProgressMonitor`) and
  `NavigatorHandlerObjectOpen.openEntityEditor` opening (on the UI thread via
  `Display.syncExec`).

### Still requires a desktop DBeaver runtime (not this headless build host)
- The WebGL + blob-Worker spike (`spike/`) — needs a display + WebView2/WebKit.
  ✅ already run with a GO verdict (§5).
- Running the view inside a live DBeaver to see the graph and navigation.
- Running `SchemaMetadataExtractor` + the navigate round-trip against a *live*
  datasource — the adapter and bridge wiring is compiler-verified but has no
  integration test (would need a real or embedded database); the graph-shaping
  and navigate-flow logic they delegate to is fully covered.

## 8. Phase 2 result — metadata adapter (verified 2026-10-29, headless)

The metadata→graph logic is now split so the one piece of real logic is
unit-testable with no DBeaver/Eclipse/OSGi runtime:

```
DBSObjectContainer ──(SchemaMetadataExtractor: adapter)──▶ GraphSource
GraphSource ──(GraphBuilder: pure algorithm)──▶ GraphModel ──▶ JSON
```

- **Why the split:** the previous extractor keyed a `HashMap<DBSEntity,Integer>`
  by provider object identity and resolved edges inline — untestable without
  mocking DBeaver's large interfaces, and fragile if a provider returns
  non-identical `DBSEntity` instances across calls. `GraphBuilder` keys by the
  stable `fullId` string instead, so correctness no longer depends on entity
  identity.
- **Test module:** `tests/org.jkiss.dbeaver.ext.graph3d.ui.tests` — a
  **standalone plain-Maven JUnit 5 module** (no Tycho parent) that compiles the
  three pure sources via `build-helper` and runs under Surefire. Built by
  `mvn -B clean verify` at the repo root alongside the Tycho plugin module.
- **Coverage (12 tests, all green):** empty graph; node order == `entities()`
  order with dense 0-based ids; simple FK → one edge; in/out degree counts;
  self-reference (self-edge, both counts bumped); cross-container FK (counted in
  `outCount` + `unresolvedCount`, no edge); parallel FKs (multiple edges + counts);
  duplicate entity id (keep-first); null-target defensive skip; JSON escaping;
  exact end-to-end JSON; degree invariants on a cyclic graph with a chord.

> `Reactor Summary: graph3d-parent SUCCESS, ...ui SUCCESS (5 src), ...ui.tests SUCCESS (Tests run: 12, Failures: 0). BUILD SUCCESS.`

A first test run caught a real defect — the test fake was pre-deduplicating
entities, so the builder's keep-first path was never exercised; fixed by having
the fake retain genuine duplicates.

## 9. Phase 3 result — navigation + details (verified 2026-10-29, headless)

The JS→Java navigate round-trip is wired end to end, with the control flow kept
pure and unit-tested while `Graph3DView` carries only DBeaver wiring + SWT
thread marshalling.

**Navigate flow** (`navigateToObject(fullId)` BrowserFunction):
```
JS navigateToObject(fullId)
  → NavigationController.navigate(fullId)          [pure: validate]
      → ObjectResolver.resolve(fullId)             [DBeaver: DBUtils.findObjectById,
                                                     off UI thread, VoidProgressMonitor]
      → EditorOpener.open(obj)                      [DBeaver: NavigatorHandlerObjectOpen
                                                     .openEntityEditor, on UI thread via
                                                     Display.syncExec]
  → Outcome{OPENED | INVALID_ID | NOT_FOUND | ERROR}  (returned to JS as status name)
```
The round-trip inverts `DBUtils.getObjectFullId` (node `path`) via
`DBUtils.findObjectById(monitor, project, fullId)`; the project comes from the
container bound to the view (`setContextProject`). Resolution runs off the UI
thread; the editor open is marshalled onto it.

**Details flow** (`requestDetails(fullId)` BrowserFunction): returns a
`NodeDetails` JSON (attributes + declared FKs) for the object-details panel.
The payload type and its JSON are complete and tested; the DBeaver-side
extraction of attributes/keys is wired with the live model in Phase 4 (for now
the bridge returns null, letting JS fall back to the in-model node summary).

- **API verification:** the plugin now compiles **8 source files** against the
  real platform via Tycho — `DBUtils.findObjectById`,
  `DBUtils.getObjectOwnerProject`, `NavigatorHandlerObjectOpen.openEntityEditor`,
  `VoidProgressMonitor`, `DBPProject`, `Display.syncExec` all resolve. Exact
  signatures were read from the resolved p2 bundle jars with `javap` (the
  shallow DBeaver clone does not contain the UI-layer sources).
- **New pure units + tests (24 total, all green):**
  - `NavigationControllerTest` (7): blank/null id short-circuits without
    resolving; NOT_FOUND without opening; resolver exception → ERROR; opener
    exception → ERROR; success passes the resolved handle through; checked
    exceptions caught; `Error` intentionally propagated (not swallowed).
  - `NodeDetailsTest` (5): `Json.quote` escaping table; empty-details JSON;
    full attributes+FKs exact JSON; escaping across all string fields;
    defensive list copy.

> `Reactor Summary: graph3d-parent SUCCESS, ...ui SUCCESS (8 src), ...ui.tests SUCCESS (Tests run: 24, Failures: 0). BUILD SUCCESS.`



## 10. Phase 4 result — viz integration (built + bundled 2026-10-29; render pending desktop)

The MIT renderer is vendored and bundled into the plugin, and `Graph3DView`
drives it end to end. Everything here is BUILT and PACKAGED on the headless host;
seeing pixels requires a desktop DBeaver (Phase 0 already proved WebGL + blob
workers light up in the embedded SWT Edge browser, so this is expected to run).

**Web build project:** `vendor-src/graph3d-web/` (durable, outside the plugin
tree) — an isolated npm project with only the renderer's runtime deps
(`pixi.js` ^8.19, `d3-force-3d` ^3.0.6) plus `esbuild`. `node build.mjs` bundles:
- `src/obsidianDomShim.ts` — installs `createEl`/`createDiv`/`createSpan`/
  `empty`/`setText` on `HTMLElement.prototype` before the renderer loads (the
  "~10% thin shim"; the renderer's `cssColorToNumber` calls `host.createDiv()`).
- `src/layout.ts` — main-thread `d3-force-3d` simulation emitting stride-3
  `Float32Array` frames to `GraphRenderer.updatePositions` (the documented
  worker-less path; the blob worker is a later optimization, same contract).
- `src/main.ts` — constructs `GraphRenderer(callbacks)`, `await init(host)`,
  exposes `window.__graph3d.setModel(json)`, wires `onNodeClick → showDetails`
  and `onNodeDoubleClick/MiddleClick → window.navigateToObject(path)`, and
  renders the details panel from `window.requestDetails(path)` (falling back to
  the in-model node summary when the bridge returns null).

An esbuild alias maps `@agv/*` to the vendored `advanced_graph_view/src`, and
`nodePaths` resolves `pixi.js`/`d3-force-3d` from the web project. Output:
`web/graph3d.bundle.js` (~623 KB minified IIFE) + `web/index.html`, written
straight into the plugin bundle. **This is the concrete proof the ~80% viz-layer
reuse bundles as one artifact** — the entire vendored `render/`, `data/` model
types, Pixi.js v8 and d3-force-3d compiled and linked with only the shim +
layout + entry as new glue.

**Java side:** `build.properties` now ships `web/`; `Graph3DView`:
- resolves `web/index.html` from the bundle via
  `FrameworkUtil.getBundle(...).getEntry(...)` + `FileLocator.toFileURL(...)`
  (unpacks from the jar) and loads it into the SWT `Browser`;
- buffers a `GraphModel` and, on `ProgressListener.completed`, pushes it with
  `browser.execute("window.__graph3d.setModel(" + <escaped JSON> + ")")`;
- the JSON goes through a JS-string-literal escaper (including `<` → `\x3c` to
  avoid any `</script>` breakout).

The Java↔JS contract matches exactly: the renderer's `GraphModel` type is
`{nodes:[{id,path,name,inCount,outCount,unresolvedCount}], edges:[{source,target,weight}]}`
— the precise shape `GraphModel.toJson()` emits; `pathToId` is rebuilt in the
entry.

- **Verification:** `mvn -B clean verify` → **BUILD SUCCESS** (plugin 8 src, 24
  tests green); `jar tf` confirms `web/index.html` and `web/graph3d.bundle.js`
  are packaged inside `org.jkiss.dbeaver.ext.graph3d.ui-0.1.0-SNAPSHOT.jar`.
- **Still desktop-only:** the actual render, a node double-click opening the
  real editor, and feeding a live schema (bind a datasource → `setModel`;
  connect `requestDetails` to real attribute/FK extraction). The bundle also
  runs standalone in a plain browser via `index.html?demo` for dev.

**Rebuilding the web layer:** `cd vendor-src/graph3d-web && node build.mjs`
(after `npm install`). Then `mvn -B clean verify` to repackage the jar.


## 11. Phase 5 result — feature + p2 update site (verified 2026-10-29, headless)

The plugin is now packaged as a standard installable Eclipse feature served
from a p2 update site, so it drops into DBeaver CE with no manual jar copying.

**New reactor modules:**
- `features/org.jkiss.dbeaver.ext.graph3d.feature` (`eclipse-feature`) —
  `feature.xml` wraps the single bundle, EPL-2.0, with the third-party MIT/ISC/
  BSD notice referenced. `unpack="false"` (the bundle loads the web assets from
  inside the jar via `FileLocator`, which handles the unpack).
- `site/org.jkiss.dbeaver.ext.graph3d.site` (`eclipse-repository`) —
  `category.xml` puts the feature under a "DBeaver 3D Schema Graph" category;
  `tycho-p2-repository-plugin` assembles `target/repository/` and a zipped
  archive. `includeAllDependencies=false` keeps only our feature+bundle (the
  Eclipse/DBeaver platform is already present in the target IDE).

**Build output** (`mvn -B clean verify`, all 5 modules SUCCESS, 24 tests green):
```
site/.../target/repository/
  content.jar  artifacts.jar  p2.index
  features/org.jkiss.dbeaver.ext.graph3d.feature_0.1.0.<qualifier>.jar
  plugins/org.jkiss.dbeaver.ext.graph3d.ui_0.1.0.<qualifier>.jar  (~213 KB, contains web/)
site/.../target/org.jkiss.dbeaver.ext.graph3d.site-0.1.0-SNAPSHOT.zip  (~219 KB)
```

**Installing into DBeaver CE (desktop):**
1. DBeaver → **Help → Install New Software…**
2. **Add… → Archive…** and pick the site zip
   (`site/org.jkiss.dbeaver.ext.graph3d.site/target/*.zip`), OR **Add… → Local…**
   and pick the `target/repository/` folder.
3. Tick **DBeaver 3D Schema Graph**, Next, accept EPL-2.0, Finish, restart.
4. Open the view: **Window → Show View → Other… → DBeaver 3D Graph → 3D Schema
   Graph** (the `org.eclipse.ui.views` contribution).

> `Reactor Summary: parent, ...ui, ...feature, ...site, ...ui.tests — all SUCCESS. BUILD SUCCESS.`

This is the artifact to run the end-to-end desktop verification against (render,
select, navigate). The view currently loads the renderer and shows the details
panel; feeding a live schema (bind a datasource → `setModel`) and wiring
`requestDetails` to real attribute/FK extraction is the remaining functional
work before it draws a real database.



## 12. Live binding + navigator command (verified 2026-10-29, headless)

The view now loads a real schema, triggered from the Database Navigator, with a
production-safe bounded read.

**Bounded, cancellable extract.** `SchemaMetadataExtractor` is capped at
`DEFAULT_MAX_TABLES = 500` and checks `DBRProgressMonitor.isCanceled()` between
entities, returning `ExtractResult{model, includedTables, totalTables,
truncated, cancelled}`. This directly addresses the production-safety concern:
the catalog read is bounded (no unbounded burst on a huge schema) and aborts
promptly when the view is closed or the Progress-view cancel is hit.

**View loading.** `Graph3DView.loadContainer(DBSObjectContainer)` runs the
bounded extract on a background `AbstractJob` (off the UI thread), resolves the
owning project via `DBUtils.getObjectOwnerProject` for navigate round-trips, and
on completion pushes the model + an honest status line onto the UI thread:
- truncated: *"Showing first 500 of N tables (capped for safety) · M relationships"*
- normal: *"K tables · M relationships"*
The idle message (no schema loaded) now reads *"No schema loaded — open the view
from a database schema."* instead of a permanent "Loading…".

**Navigator command.** `OpenGraph3DHandler` + `plugin.xml`:
- `org.eclipse.ui.commands` command `...openGraph` ("Open in 3D Schema Graph");
- `org.eclipse.ui.handlers` binds it to the handler;
- `org.eclipse.ui.menus` contributes it to the navigator popup
  (`popup:org.eclipse.ui.popup.any?after=tools`, the same popup DBeaver's own
  navigator tools use), `visibleWhen` the selection adapts to `DBSObject`.
The handler reads the selection, resolves `DBNDatabaseNode.getObject()` to a
`DBSObjectContainer` (it validates this itself, so it safely no-ops on
non-container nodes rather than relying on a fragile declarative expression),
`page.showView(Graph3DView.VIEW_ID)`, then `loadContainer(container)`.
Added `org.eclipse.core.commands` + `org.eclipse.jface` to `Require-Bundle`.

**End-to-end chain now complete** (build-verified; runtime is the user's):
right-click a schema/catalog → *Open in 3D Schema Graph* → bounded metadata read
on a background job → `GraphModel` JSON → Pixi renderer → double-click a node →
`navigateToObject` → `DBUtils.findObjectById` → `openEntityEditor`.

> `mvn -B clean verify`: 5 modules SUCCESS, plugin compiles **9 source files**,
> 24 tests green, p2 site rebuilt.

**Remaining (optional) functional polish:**
- `requestDetails` still returns null → the details panel uses the in-model node
  summary; wiring it to real `DBSEntity.getAttributes()`/`getAssociations()`
  extraction would show columns + key types.
- A user preference for the table cap / schema scope.
