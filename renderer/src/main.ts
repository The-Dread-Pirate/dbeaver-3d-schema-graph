/*
 * DBeaver 3D Schema Graph — browser bundle entry.
 *
 * Self-contained host for the vendored MIT renderer, with NO Obsidian runtime.
 * Responsibilities:
 *   1. install the Obsidian DOM shim before the renderer module loads;
 *   2. construct GraphRenderer with callbacks wired to the Java bridges
 *      (window.navigateToObject / window.requestDetails injected as SWT
 *      BrowserFunctions);
 *   3. expose window.__graph3d.setModel(json) for the Java side to push a
 *      GraphModel, and drive a d3-force-3d layout feeding updatePositions;
 *   4. render a details panel from requestDetails, falling back to the
 *      in-model node summary when the bridge returns null.
 *
 * The Java↔JS contract is exactly the GraphModel JSON that GraphModel.toJson()
 * emits: { nodes:[{id,path,name,inCount,outCount,unresolvedCount}],
 *          edges:[{source,target,weight}] }.
 */
import { installObsidianDomShim } from "./obsidianDomShim";
installObsidianDomShim();

import { GraphRenderer, type RendererCallbacks } from "@agv/render/GraphRenderer";
import type { GraphModel, GraphNode } from "@agv/data/GraphStore";
import { startForceLayout, type LayoutHandle } from "./layout";
import { encodeByConnectivity } from "./encoding";

/** Shape pushed from Java (GraphModel.toJson). pathToId is built here. */
interface WireModel {
  nodes: GraphNode[];
  edges: { source: number; target: number; weight: number; kind?: string }[];
}

/** Bridges injected by the SWT host as BrowserFunctions. Optional so the page
 *  also runs standalone in a plain browser for development. */
declare global {
  interface Window {
    navigateToObject?: (fullId: string) => string | void;
    requestDetails?: (fullId: string) => string | null | void;
    __graph3d?: Graph3DHost;
  }
}

interface NodeDetailsWire {
  fullId: string;
  name: string;
  type: string;
  attributes: { name: string; type: string; nullable: boolean; keyType: string }[];
  foreignKeys: { name: string; refTable: string; columns: string }[];
}

class Graph3DHost {
  private renderer: GraphRenderer | null = null;
  private layout: LayoutHandle | null = null;
  private model: GraphModel | null = null;
  /** True once the view has been framed for the current model. Gates the
   *  ResizeObserver so it auto-fits only on the first real size, not on every
   *  later pane resize. Reset when a new model is loaded. */
  private didInitialFit = false;
  private readonly graphHost: HTMLElement;
  private readonly detailsHost: HTMLElement;

  constructor(graphHost: HTMLElement, detailsHost: HTMLElement) {
    this.graphHost = graphHost;
    this.detailsHost = detailsHost;
  }

  async init(): Promise<void> {
    const callbacks: RendererCallbacks = {
      onNodeHover: () => {},
      onNodeClick: (nodeId) => this.showDetails(nodeId),
      onNodeDoubleClick: (nodeId) => this.navigate(nodeId),
      onNodeMiddleClick: (nodeId) => this.navigate(nodeId),
      onNodeContextMenu: () => {},
      onNodeDragStart: () => {},
      onNodeDrag: () => {},
      onNodeDragEnd: () => {},
      onLassoSelect: () => {},
      onExploreAim: () => {},
      onExploreJump: () => {},
      onContextLost: () => {
        this.setStatus("WebGL context lost — resize or reopen the view to rebuild.");
      },
    };
    this.renderer = new GraphRenderer(callbacks);
    await this.renderer.init(this.graphHost);
    // Clean canvas by default: only the hovered table and its connections are
    // named, so the graph isn't a wall of text. Hovering reveals names.
    this.renderer.setLabelsHoverOnly(true);

    // The SWT view often has zero size at init, so Pixi creates a 0x0 canvas
    // and draws nothing. DBeaver/SWT panes also resize without firing
    // window.resize. Observe the host and re-size + re-fit the renderer whenever
    // it gains a real size or changes — otherwise the graph stays invisible.
    const ro = new ResizeObserver(() => {
      if (!this.renderer) return;
      try {
        this.renderer.resize();
        // Auto-fit ONLY on the first transition from a zero-size pane to a real
        // size (the original black-canvas fix). After that, a pane resize — the
        // details panel appearing, the window changing — must NOT re-frame, or
        // it fights the user's own camera position and snaps the view back.
        const sized = this.graphHost.clientWidth > 0 && this.graphHost.clientHeight > 0;
        if (this.model && sized && !this.didInitialFit) {
          this.didInitialFit = true;
          this.renderer.fitAll();
        }
      } catch {
        /* resize is best-effort */
      }
    });
    ro.observe(this.graphHost);
  }

  /** Called from Java: push a GraphModel as a JSON string. */
  setModel(json: string): void {
    let wire: WireModel;
    try {
      wire = JSON.parse(json) as WireModel;
    } catch (e) {
      this.setStatus("Failed to parse graph model JSON: " + String(e));
      return;
    }
    const pathToId = new Map<string, number>();
    for (const n of wire.nodes) pathToId.set(n.path, n.id);
    const model: GraphModel = { nodes: wire.nodes, edges: wire.edges, pathToId };
    this.model = model;
    this.didInitialFit = false; // a new model gets one fresh auto-fit

    if (!this.renderer) return;
    try {
      this.renderer.setModel(model);
      // Color table orbs by FK connectivity (cool = leaf, hot = hub) and make
      // the busiest tables glow brighter, so the schema's structure reads at a
      // glance instead of every node being one flat color.
      const { tints, glow } = encodeByConnectivity(model.nodes);
      this.renderer.applyNodeColors(tints, glow);
    } catch (e) {
      showFatal("renderer.setModel failed: " + describeError(e));
      return;
    }

    this.layout?.stop();
    let framed = false;
    this.layout = startForceLayout(
      model.nodes.length,
      model.edges,
      (positions3) => {
        try {
          this.renderer?.updatePositions(positions3);
          // Frame the graph on the first layout frame — unless the user already
          // grabbed the camera, in which case respect their view. Mark framed
          // either way so this one-shot never re-fires on later layout ticks.
          if (!framed && this.renderer) {
            framed = true;
            this.didInitialFit = true; // observer need not fit again
            if (!this.renderer.isCameraTouched) {
              this.renderer.fitAll();
            }
          }
        } catch (e) {
          this.layout?.stop();
          showFatal("layout/render frame failed: " + describeError(e));
        }
      },
      () => {
        try {
          // Re-frame when the layout settles — but ONLY if the user hasn't
          // taken the camera somewhere themselves. Otherwise flying inside the
          // cluster would be yanked back out to frame-all the moment the
          // physics cools. The initial first-frame fit above still runs.
          if (this.renderer && !this.renderer.isCameraTouched) {
            this.renderer.fitAll();
          }
        } catch (e) {
          showFatal("fitAll failed: " + describeError(e));
        }
      }
    );

    const w = this.graphHost.clientWidth;
    const h = this.graphHost.clientHeight;
    this.setStatus(
      `${model.nodes.length} tables, ${model.edges.length} relationships · canvas ${w}x${h}`);
  }

  private navigate(nodeId: number): void {
    const node = this.model?.nodes[nodeId];
    if (!node) return;
    if (typeof window.navigateToObject === "function") {
      window.navigateToObject(node.path);
    }
  }

  private showDetails(nodeId: number): void {
    const node = this.model?.nodes[nodeId];
    if (!node) return;

    // Prefer rich details from DBeaver; fall back to the in-model node summary.
    let details: NodeDetailsWire | null = null;
    if (typeof window.requestDetails === "function") {
      const raw = window.requestDetails(node.path);
      if (typeof raw === "string" && raw.length > 0) {
        try {
          details = JSON.parse(raw) as NodeDetailsWire;
        } catch {
          details = null;
        }
      }
    }
    this.renderDetails(nodeId, node, details);
  }

  /** Names of tables connected to `nodeId`, split by relationship direction
   *  and kind, computed from the in-memory graph edges. */
  private connectedTables(nodeId: number): {
    references: string[];      // this table -> others (FK out / view refs)
    referencedBy: string[];    // others -> this table (FK in)
  } {
    const references: string[] = [];
    const referencedBy: string[] = [];
    const seenOut = new Set<number>();
    const seenIn = new Set<number>();
    const model = this.model;
    if (!model) return { references, referencedBy };
    const nameOf = (id: number) => model.nodes[id]?.name ?? `#${id}`;
    for (const e of model.edges) {
      if (e.source === nodeId && e.target !== nodeId && !seenOut.has(e.target)) {
        seenOut.add(e.target);
        references.push(nameOf(e.target));
      } else if (e.target === nodeId && e.source !== nodeId && !seenIn.has(e.source)) {
        seenIn.add(e.source);
        referencedBy.push(nameOf(e.source));
      }
    }
    references.sort((a, b) => a.localeCompare(b));
    referencedBy.sort((a, b) => a.localeCompare(b));
    return { references, referencedBy };
  }

  private renderDetails(nodeId: number, node: GraphNode, details: NodeDetailsWire | null): void {
    this.detailsHost.empty();
    const title = this.detailsHost.createEl("h3", { text: details?.name ?? node.name });
    title.style.margin = "0 0 .5rem";

    const meta = this.detailsHost.createDiv();
    meta.style.opacity = "0.7";
    meta.style.fontSize = "12px";
    meta.textContent = `${details?.type ?? "TABLE"} · ${node.outCount} FK out · ${node.inCount} FK in`;

    if (details && details.attributes.length > 0) {
      const h = this.detailsHost.createEl("h4", { text: "Columns" });
      h.style.margin = ".75rem 0 .25rem";
      const ul = this.detailsHost.createEl("ul");
      ul.style.margin = "0";
      ul.style.paddingLeft = "1.1rem";
      for (const a of details.attributes) {
        const li = ul.createEl("li");
        const key = a.keyType ? ` [${a.keyType}]` : "";
        const nul = a.nullable ? "" : " NOT NULL";
        li.textContent = `${a.name}: ${a.type}${nul}${key}`;
      }
    }

    if (details && details.foreignKeys.length > 0) {
      const h = this.detailsHost.createEl("h4", { text: "Foreign keys" });
      h.style.margin = ".75rem 0 .25rem";
      const ul = this.detailsHost.createEl("ul");
      ul.style.margin = "0";
      ul.style.paddingLeft = "1.1rem";
      for (const fk of details.foreignKeys) {
        const li = ul.createEl("li");
        li.textContent = `${fk.name} → ${fk.refTable} (${fk.columns})`;
      }
    }

    // Connected tables — the names of the related objects that light up when
    // you hover this one. Computed from the graph edges, so it works even when
    // DBeaver returns no rich details.
    const { references, referencedBy } = this.connectedTables(nodeId);
    this.renderNameList("References", references);
    this.renderNameList("Referenced by", referencedBy);

    const hint = this.detailsHost.createDiv();
    hint.style.marginTop = ".75rem";
    hint.style.opacity = "0.6";
    hint.style.fontSize = "11px";
    hint.textContent = "Double-click a node to open it in DBeaver.";
  }

  /** Render a titled list of connected-table names; no-op when the list is
   *  empty so the pane stays clean for leaf tables. */
  private renderNameList(title: string, names: string[]): void {
    if (names.length === 0) return;
    const h = this.detailsHost.createEl("h4", { text: `${title} (${names.length})` });
    h.style.margin = ".75rem 0 .25rem";
    const ul = this.detailsHost.createEl("ul");
    ul.style.margin = "0";
    ul.style.paddingLeft = "1.1rem";
    for (const name of names) {
      ul.createEl("li", { text: name });
    }
  }

  private setStatus(text: string): void {
    const el = document.getElementById("graph3d-status");
    if (el) el.textContent = text;
  }
}

async function bootstrap(): Promise<void> {
  const graphHost = document.getElementById("graph3d-canvas-host");
  const detailsHost = document.getElementById("graph3d-details");
  if (!graphHost || !detailsHost) {
    showFatal("graph3d: host elements missing");
    return;
  }

  // Surface any runtime failure ON THE PAGE — a thrown error in the embedded
  // WebView2 otherwise leaves a silent black canvas with no feedback.
  window.addEventListener("error", (e) => showFatal("Error: " + (e.message || e.error)));
  window.addEventListener("unhandledrejection", (e) =>
    showFatal("Promise rejection: " + String((e as PromiseRejectionEvent).reason)));

  try {
    const host = new Graph3DHost(graphHost, detailsHost);
    window.__graph3d = host;
    await host.init();
    setStatusBar("Renderer ready" + (location.search.includes("demo") ? " (demo)" : ""));

    if (location.search.includes("demo")) {
      host.setModel(JSON.stringify({
        nodes: [
          { id: 0, path: "demo.orders", name: "orders", inCount: 1, outCount: 1, unresolvedCount: 0 },
          { id: 1, path: "demo.customers", name: "customers", inCount: 1, outCount: 0, unresolvedCount: 0 },
          { id: 2, path: "demo.items", name: "items", inCount: 0, outCount: 1, unresolvedCount: 0 },
        ],
        edges: [
          { source: 0, target: 1, weight: 1 },
          { source: 2, target: 0, weight: 1 },
        ],
      }));
    }
  } catch (e) {
    showFatal("Startup failed: " + describeError(e));
  }
}

function setStatusBar(text: string): void {
  const el = document.getElementById("graph3d-status");
  if (el) el.textContent = text;
}

function describeError(e: unknown): string {
  if (e instanceof Error) return e.message + (e.stack ? "\n" + e.stack : "");
  return String(e);
}

/** Paint a visible error banner over the canvas host and the status bar. */
function showFatal(message: string): void {
  setStatusBar(message.split("\n")[0]);
  const host = document.getElementById("graph3d-canvas-host");
  if (!host) return;
  let box = document.getElementById("graph3d-fatal");
  if (!box) {
    box = document.createElement("div");
    box.id = "graph3d-fatal";
    box.style.cssText =
      "position:absolute;left:0;top:0;right:0;max-height:100%;overflow:auto;"
      + "padding:12px 16px;background:rgba(120,20,20,.92);color:#fff;"
      + "font:12px/1.4 monospace;white-space:pre-wrap;z-index:9999;";
    host.appendChild(box);
  }
  box.textContent = "⚠ 3D Schema Graph error\n\n" + message;
  // eslint-disable-next-line no-console
  console.error("[graph3d]", message);
}

void bootstrap();
