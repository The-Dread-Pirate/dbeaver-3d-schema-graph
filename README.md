# DBeaver 3D Schema Graph

An open-source DBeaver Community Edition plugin that visualizes database object
relationships as an interactive 3D graph: tables are nodes, foreign keys are
edges. Reuses the rendering core of the Obsidian plugin
[advanced_graph_view](https://github.com/n23eos/advanced_graph_view) (MIT),
embedded in an Eclipse SWT `Browser` widget and driven by DBeaver's existing
metadata APIs.

## Status

Working end to end. Right-click a schema or database in the Database
Navigator and the tables render as nodes, with foreign keys (and view-to-table
references) as edges, in a pseudo-3D camera you can orbit, pan and zoom.

**Tested on:** DBeaver Community Edition on Windows (embedded WebView2 browser),
built against the DBeaver CE update site as of November 2026. macOS and Linux
use a different embedded browser backend and are **untested**; WebGL support
there is the main risk.

See [`docs/DESIGN.md`](docs/DESIGN.md) for the full architecture and reuse
analysis, and [`spike/`](spike/) for the original runtime probe.

## Installing

### From the update site (recommended, supports updates)

1. In DBeaver: **Help → Install New Software… → Add…**
2. Name: `DBeaver 3D Graph`, Location:
   `https://the-dread-pirate.github.io/dbeaver-3d-schema-graph/`
3. Tick **DBeaver 3D Graph**, then **Next** through the wizard and restart
   DBeaver when prompted.

DBeaver will warn that the content is **unsigned**. That is expected: the
plugin is not code-signed. Later versions arrive through
**Help → Check for Updates**.

### From a zip

Download or build `org.jkiss.dbeaver.ext.graph3d.site-*.zip`, then use
**Help → Install New Software… → Add… → Archive…** and pick the zip.

## Using it

Right-click a node in the Database Navigator:

- **Open in 3D Database Neural View** loads every table as its own node (up to
  the table limit).
- **Open in 3D Database Overview** loads one node per schema, linked by the
  number of cross-schema relationships. Double-click a schema to expand it
  into its tables.

In the graph:

- **Left-drag** anywhere spins the view. **Right-drag** pans. **Scroll** flies
  the camera forward and back.
- **Hover** a table to see its name and highlight what it connects to.
- **Click** a table for its schema, FK counts, and the tables it references or
  is referenced by (shown as `schema.table`).
- **Double-click** a table to open it in DBeaver.
- **Alt + left-drag** a table to reposition it.
- The **Set table limit…** toolbar button changes how many tables are read
  (default 2000). Large limits read more catalog metadata and render more
  nodes, so raise it gradually on production databases.

## Building from source

The plugin has two build layers: the **web renderer** (TypeScript → a single
JS bundle) and the **OSGi plugin** (Java/Tycho → the installable p2 site).

### 1. Web renderer (`renderer/`)

The `renderer/` directory is self-contained: it holds the DBeaver host code
(`renderer/src/`) and the vendored MIT renderer core (`renderer/vendor/`, see
[`renderer/VENDOR.md`](renderer/VENDOR.md) for provenance). It bundles with
esbuild straight into the plugin's `web/` directory.

```bash
cd renderer
npm ci            # or: npm install
npm run build     # → plugins/org.jkiss.dbeaver.ext.graph3d.ui/web/graph3d.bundle.js + index.html
npm run typecheck # optional: strict tsc check of the sources
```

The built `web/graph3d.bundle.js` and `web/index.html` are **committed** so the
Maven build below works without Node. Re-run `npm run build` whenever you change
anything under `renderer/` and commit the regenerated bundle alongside the
source change.

### 2. OSGi plugin (Maven / Tycho)

Requires JDK 21 and Maven. From the repository root:

```bash
mvn clean verify
```

This compiles the Java plugin, runs the tests, and assembles the installable p2
site at
`site/org.jkiss.dbeaver.ext.graph3d.site/target/org.jkiss.dbeaver.ext.graph3d.site-*.zip`.
Install it in DBeaver via **Help → Install New Software → Add → Archive**.

## Publishing the update site

The update site is the contents of
`site/org.jkiss.dbeaver.ext.graph3d.site/target/repository/` from a successful
`mvn clean verify`, served by GitHub Pages from the `gh-pages` branch (Settings
→ Pages → Deploy from a branch → `gh-pages` / root). To publish a new version,
replace the files on `gh-pages` with the new `repository/` contents and keep
`.nojekyll` in place. Users then pick the update up with **Help → Check for
Updates**. Each build gets a fresh version qualifier, so the new version is
always newer than the old one.

## Licensing

- This plugin: **EPL-2.0**, see [`LICENSE`](LICENSE).
- Reused Obsidian viz code: MIT. See [`THIRD-PARTY.md`](THIRD-PARTY.md).
- DBeaver CE APIs: Apache-2.0 (linked, not modified).
