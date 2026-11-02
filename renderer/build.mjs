// esbuild bundler for the DBeaver 3D graph web layer.
//
// Bundles src/main.ts + the vendored MIT renderer (vendor/, from
// advanced_graph_view) + pixi.js + d3-force-3d into a single self-contained
// IIFE and writes it, together with index.html, into the plugin bundle's web/
// dir so Tycho packages it into the OSGi jar.
//
// Run from this directory: `npm ci && npm run build`.
import { build } from "esbuild";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import { mkdirSync, copyFileSync } from "node:fs";

const here = dirname(fileURLToPath(import.meta.url));
// The vendored renderer source now lives inside this project (self-building).
const vendorSrc = resolve(here, "vendor");
const outDir = resolve(
  here, "..",
  "plugins", "org.jkiss.dbeaver.ext.graph3d.ui", "web"
);

mkdirSync(outDir, { recursive: true });

// Map the "@agv/*" alias to the vendored renderer source tree.
const agvAlias = {
  name: "agv-alias",
  setup(b) {
    b.onResolve({ filter: /^@agv\// }, (args) => ({
      path: resolve(vendorSrc, args.path.slice("@agv/".length) + ".ts"),
    }));
  },
};

await build({
  entryPoints: [resolve(here, "src", "main.ts")],
  bundle: true,
  format: "iife",
  platform: "browser",
  // Resolve bare imports (pixi.js, d3-force-3d) from THIS project's
  // node_modules even when the importing file lives in the vendored tree.
  nodePaths: [resolve(here, "node_modules")],
  absWorkingDir: here,
  target: ["chrome110"], // WebView2/Edge baseline proven by the Phase 0 spike
  outfile: resolve(outDir, "graph3d.bundle.js"),
  plugins: [agvAlias],
  loader: { ".css": "text" },
  logLevel: "info",
  sourcemap: false,
  minify: true,
  define: { "process.env.NODE_ENV": '"production"' },
});

// Static host page.
copyFileSync(resolve(here, "src", "index.html"), resolve(outDir, "index.html"));

console.log("graph3d web bundle written to", outDir);
