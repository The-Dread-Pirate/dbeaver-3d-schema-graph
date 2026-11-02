# DBeaver 3D Schema Graph

An open-source DBeaver Community Edition plugin that visualizes database object
relationships as an interactive 3D graph: tables are nodes, foreign keys are
edges. Reuses the rendering core of the Obsidian plugin
[advanced_graph_view](https://github.com/n23eos/advanced_graph_view) (MIT),
embedded in an Eclipse SWT `Browser` widget and driven by DBeaver's existing
metadata APIs.

## Status

Working end to end: right-click a schema/catalog in the Database Navigator →
**Open in 3D Schema Graph** → the tables render as nodes with their foreign
keys as edges, framed in a pseudo-3D camera you can orbit, pan and zoom.

See [`docs/DESIGN.md`](docs/DESIGN.md) for the full architecture and reuse
analysis, and [`spike/`](spike/) for the original runtime probe.

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

## Licensing

- This plugin: **EPL-2.0** (Eclipse-ecosystem norm, Apache-2.0 compatible).
- Reused Obsidian viz code: MIT. See [`THIRD-PARTY.md`](THIRD-PARTY.md).
- DBeaver CE APIs: Apache-2.0 (linked, not modified).
