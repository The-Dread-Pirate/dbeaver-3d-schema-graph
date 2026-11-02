# Vendored renderer provenance

The `vendor/` directory contains a subset of the **Advanced Graph View**
Obsidian plugin's Pixi.js renderer, reused under its MIT license.

| | |
|---|---|
| Upstream project | Advanced Graph View (`graph-insight`) |
| Author | n23eos |
| Repository | https://github.com/n23eos/advanced_graph_view |
| Version | 0.10.1 |
| Commit | `17e3eb1fd0e64d4d13d9a130c4bd77b2f287b48d` |
| License | MIT (see `LICENSE` in this directory) |

## What was vendored

Only the modules transitively reachable from `@agv/render/GraphRenderer` and
`@agv/data/GraphStore` were copied — the standalone renderer core, with none of
the Obsidian-specific UI, i18n, settings, or worker code. The source paths are
preserved under `vendor/` (e.g. `vendor/render/GraphRenderer.ts` mirrors the
upstream `src/render/GraphRenderer.ts`).

Current set (18 files):

```
vendor/analysis/geometry.ts
vendor/analysis/hull.ts
vendor/data/GraphStore.ts
vendor/encoding/colorScales.ts
vendor/explore/aiming.ts
vendor/render/clickDispatcher.ts
vendor/render/dragMath.ts
vendor/render/EdgeMesh.ts
vendor/render/GraphRenderer.ts
vendor/render/hitTest.ts
vendor/render/labelCollision.ts
vendor/render/nodeAppearance.ts
vendor/render/nodeStyle.ts
vendor/render/NodeTexture.ts
vendor/render/positionSmoothing.ts
vendor/render/projection.ts
vendor/render/resizeGate.ts
vendor/render/Viewport.ts
```

The `@agv/*` import alias (configured in `tsconfig.json` and `build.mjs`)
maps to `./vendor/*`.

## How to refresh from upstream

1. Check out the desired upstream commit of `advanced_graph_view`.
2. Re-run the import trace from `render/GraphRenderer.ts` + `data/GraphStore.ts`
   to confirm the reachable set hasn't grown.
3. Copy the reachable `src/**` files into `vendor/`, preserving paths.
4. Update the commit hash and file list above.

## Local modifications

Performance and DBeaver-integration changes made after vendoring are tracked in
this repository's git history. Keep this file's commit pointer in sync so the
diff against upstream stays recoverable.
