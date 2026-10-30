# DBeaver 3D Schema Graph

An open-source DBeaver Community Edition plugin that visualizes database object
relationships as an interactive 3D graph: tables are nodes, foreign keys are
edges. Reuses the rendering core of the Obsidian plugin
[advanced_graph_view](https://github.com/n23eos/advanced_graph_view) (MIT),
embedded in an Eclipse SWT `Browser` widget and driven by DBeaver's existing
metadata APIs.

## Status

Phase 0 — **runtime de-risk spike**. Before committing to the full plugin we
must prove that the embedded SWT `Browser` backend on each target OS can run:

1. a **WebGL** context (Pixi.js v8 requires it), and
2. a **blob-URL Web Worker** (the force-layout engine runs in one; there is a
   main-thread fallback if this fails).

See [`docs/DESIGN.md`](docs/DESIGN.md) for the full architecture and reuse
analysis, and [`spike/`](spike/) for the runtime probe.

## Licensing

- This plugin: **EPL-2.0** (Eclipse-ecosystem norm, Apache-2.0 compatible).
- Reused Obsidian viz code: MIT. See [`THIRD-PARTY.md`](THIRD-PARTY.md).
- DBeaver CE APIs: Apache-2.0 (linked, not modified).
