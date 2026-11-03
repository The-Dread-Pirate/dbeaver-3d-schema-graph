/*
 * DBeaver 3D Schema Graph — browser bundle
 * Main-thread force layout.
 *
 * Phase 0 proved blob Web Workers run in the embedded SWT Edge browser, so the
 * worker path is available; this first integration drives the SAME d3-force-3d
 * simulation on the main thread (the renderer's documented `isStatic`-style
 * fallback) to keep the bundle simple and dependency-contained. Swapping in the
 * worker is a later optimization and does not change the renderer contract:
 * either way we hand `GraphRenderer.updatePositions` a stride-3 Float32Array.
 */
import {
  forceSimulation,
  forceLink,
  forceManyBody,
  forceX,
  forceY,
  forceZ,
  type SimulationNodeDatum3D,
} from "d3-force-3d";

interface SimNode extends SimulationNodeDatum3D {
  index: number;
}

interface SimLink {
  source: number;
  target: number;
}

export interface LayoutHandle {
  stop(): void;
}

/**
 * Start a 3D force layout over `nodeCount` nodes and `edges`, invoking
 * `onFrame(positions3)` on each animation frame with a fresh stride-3
 * Float32Array [x0,y0,z0, x1,y1,z1, ...]. Runs until the simulation cools
 * below alphaMin or {@link LayoutHandle.stop} is called.
 */
export function startForceLayout(
  nodeCount: number,
  edges: ReadonlyArray<{ source: number; target: number }>,
  onFrame: (positions3: Float32Array) => void,
  onSettled?: () => void,
  schemas?: ReadonlyArray<string>
): LayoutHandle {
  const nodes: SimNode[] = [];
  for (let i = 0; i < nodeCount; i++) {
    nodes.push({ index: i });
  }
  const links: SimLink[] = edges.map((e) => ({ source: e.source, target: e.target }));

  // Schema clustering: when the nodes span more than one schema, give each
  // schema a center spread around a circle and pull its nodes toward it, so the
  // graph separates into per-schema regions instead of one congested tangle.
  // A single-schema (or schema-less) graph keeps the original origin-centered
  // layout unchanged.
  const centers = computeSchemaCenters(nodeCount, schemas);
  const targetX = centers
    ? (_d: SimulationNodeDatum3D, i: number) => centers.x[i]
    : 0;
  const targetY = centers
    ? (_d: SimulationNodeDatum3D, i: number) => centers.y[i]
    : 0;
  const targetZ = centers
    ? (_d: SimulationNodeDatum3D, i: number) => centers.z[i]
    : 0;
  // A touch more pull toward the schema center than the old global centering,
  // so clusters stay coherent without collapsing (charge still spreads them).
  const centerStrength = centers ? 0.12 : 0.05;

  const sim = forceSimulation<SimNode>(nodes, 3)
    .force("charge", forceManyBody().strength(-30).distanceMax(400))
    .force("link", forceLink(links).id((d: unknown) => (d as SimNode).index).distance(40).strength(0.4))
    .force("x", forceX(targetX).strength(centerStrength))
    .force("y", forceY(targetY).strength(centerStrength))
    .force("z", forceZ(targetZ).strength(centerStrength))
    .alphaMin(0.02)
    .velocityDecay(0.4);

  let running = true;
  let raf = 0;

  const emit = () => {
    const out = new Float32Array(nodeCount * 3);
    const simNodes = sim.nodes();
    for (let i = 0; i < nodeCount; i++) {
      const n = simNodes[i];
      out[i * 3] = n.x ?? 0;
      out[i * 3 + 1] = n.y ?? 0;
      out[i * 3 + 2] = n.z ?? 0;
    }
    onFrame(out);
  };

  const step = () => {
    if (!running) return;
    sim.tick(1);
    emit();
    if (sim.alpha() > sim.alphaMin()) {
      raf = requestAnimationFrame(step);
    } else {
      running = false;
      if (onSettled) onSettled();
    }
  };
  raf = requestAnimationFrame(step);

  return {
    stop() {
      running = false;
      if (raf) cancelAnimationFrame(raf);
      sim.stop();
    },
  };
}

/** Per-node schema-center target coordinates for clustering. */
interface SchemaCenters {
  x: Float32Array;
  y: Float32Array;
  z: Float32Array;
}

/**
 * Assign each distinct schema a center spread evenly around a circle (radius
 * scaled to the node count so clusters don't overlap), and return per-node
 * target coordinates pointing at that node's schema center. Returns null when
 * clustering doesn't apply — no schema info, or a single schema — so the layout
 * stays origin-centered exactly as before.
 */
function computeSchemaCenters(
  nodeCount: number,
  schemas?: ReadonlyArray<string>
): SchemaCenters | null {
  if (!schemas || schemas.length !== nodeCount) {
    return null;
  }
  // Distinct schema names in first-seen order.
  const order: string[] = [];
  const indexOf = new Map<string, number>();
  for (const s of schemas) {
    const key = s ?? "";
    if (!indexOf.has(key)) {
      indexOf.set(key, order.length);
      order.push(key);
    }
  }
  if (order.length < 2) {
    return null; // one schema (or none) — nothing to separate
  }
  // Circle radius grows with both the number of schemas and the overall node
  // count, so denser graphs get more room between clusters.
  const radius = Math.max(300, 120 * order.length + nodeCount * 1.5);
  const centerX = new Float32Array(order.length);
  const centerY = new Float32Array(order.length);
  for (let c = 0; c < order.length; c++) {
    const angle = (2 * Math.PI * c) / order.length;
    centerX[c] = Math.cos(angle) * radius;
    centerY[c] = Math.sin(angle) * radius;
  }
  const x = new Float32Array(nodeCount);
  const y = new Float32Array(nodeCount);
  const z = new Float32Array(nodeCount);
  for (let i = 0; i < nodeCount; i++) {
    const c = indexOf.get(schemas[i] ?? "") ?? 0;
    x[i] = centerX[c];
    y[i] = centerY[c];
    z[i] = 0; // keep clusters roughly coplanar; charge gives local 3D spread
  }
  return { x, y, z };
}
