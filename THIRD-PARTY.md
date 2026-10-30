# Third-Party Notices

This plugin reuses and bundles third-party software. All components below are
under permissive licenses (MIT / ISC / BSD) compatible with this project's
EPL-2.0 license.

## Reused source code

### Advanced Graph View (graph-insight)
- Source: https://github.com/n23eos/advanced_graph_view
- License: MIT, Copyright (c) 2026 n23eos
- Reused: the Obsidian-independent rendering, layout, interaction and data-model
  modules (`src/render/**`, `src/data/GraphStore.ts`, `src/workers/**`,
  `src/explore/**`, `src/analysis/**`, `src/encoding/**`, `src/query/**`).
  The Obsidian plugin shell (`main.ts`, `src/view/**`, `src/settings/**`,
  `src/ui/*Modal.ts`) is NOT reused; a DBeaver host shell replaces it.
- The full MIT license text is retained in `vendor/advanced_graph_view/LICENSE`.

## Bundled runtime dependencies (via the reused code)

| Package | License | Project |
|---|---|---|
| pixi.js ^8 | MIT | https://github.com/pixijs/pixijs |
| d3-force-3d ^3 | BSD-3-Clause | https://github.com/vasturiano/d3-force-3d |
| d3-force ^3 | ISC | https://github.com/d3/d3-force |
| graphology ^0.26 | MIT | https://github.com/graphology/graphology |
| graphology-communities-louvain ^2 | MIT | https://github.com/graphology/graphology |
| graphology-metrics ^2 | MIT | https://github.com/graphology/graphology |

## DBeaver CE (linked, not bundled or modified)
- Source: https://github.com/dbeaver/dbeaver
- License: Apache-2.0
- This plugin depends only on DBeaver's exported OSGi API packages
  (`org.jkiss.dbeaver.model.*`, `org.jkiss.dbeaver.ui*`). DBeaver core is not
  modified.
