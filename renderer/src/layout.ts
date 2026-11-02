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
  onSettled?: () => void
): LayoutHandle {
  const nodes: SimNode[] = [];
  for (let i = 0; i < nodeCount; i++) {
    nodes.push({ index: i });
  }
  const links: SimLink[] = edges.map((e) => ({ source: e.source, target: e.target }));

  const sim = forceSimulation<SimNode>(nodes, 3)
    .force("charge", forceManyBody().strength(-30).distanceMax(400))
    .force("link", forceLink(links).id((d: unknown) => (d as SimNode).index).distance(40).strength(0.4))
    .force("x", forceX(0).strength(0.05))
    .force("y", forceY(0).strength(0.05))
    .force("z", forceZ(0).strength(0.05))
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
