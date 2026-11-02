/*
 * DBeaver 3D Schema Graph — node color/size/glow encoding.
 *
 * The vendored renderer exposes applyEncoding(sizes, tints, glow) with
 * per-node arrays; this module computes those arrays from the GraphModel so
 * table orbs are colored meaningfully instead of a single flat theme color.
 *
 * Default scheme: color by FK connectivity (inCount + outCount). Low-degree
 * leaf tables sit at the cool end of the ramp, highly-connected hub tables at
 * the hot end, so the schema's structure reads at a glance. Glow scales with
 * degree too, so hubs shine brighter.
 */
import type { GraphNode } from "@agv/data/GraphStore";

/** A perceptually-ordered ramp from cool (low degree) to hot (high degree).
 *  Stops are 0xRRGGBB; intermediate nodes are linearly interpolated. */
const DEGREE_RAMP: readonly number[] = [
  0x3a7bd5, // blue      — isolated / leaf tables
  0x00b3a4, // teal
  0x7cc576, // green
  0xf2c94c, // amber
  0xf2994a, // orange
  0xeb5757, // red       — hub tables with the most foreign keys
];

function lerpChannel(a: number, b: number, t: number): number {
  return Math.round(a + (b - a) * t);
}

/** Interpolate the ramp at position t in [0,1], returning an 0xRRGGBB int. */
function sampleRamp(t: number): number {
  const clamped = t <= 0 ? 0 : t >= 1 ? 1 : t;
  const span = DEGREE_RAMP.length - 1;
  const scaled = clamped * span;
  const i = Math.min(span - 1, Math.floor(scaled));
  const frac = scaled - i;
  const c0 = DEGREE_RAMP[i];
  const c1 = DEGREE_RAMP[i + 1];
  const r = lerpChannel((c0 >> 16) & 0xff, (c1 >> 16) & 0xff, frac);
  const g = lerpChannel((c0 >> 8) & 0xff, (c1 >> 8) & 0xff, frac);
  const b = lerpChannel(c0 & 0xff, c1 & 0xff, frac);
  return (r << 16) | (g << 8) | b;
}

export interface NodeEncoding {
  tints: Int32Array;
  glow: Float32Array;
}

/**
 * Compute per-node tint + glow from FK connectivity. `degree` is normalized
 * across the model so the busiest table anchors the hot end regardless of the
 * absolute FK count (a small schema still gets the full ramp).
 */
export function encodeByConnectivity(nodes: readonly GraphNode[]): NodeEncoding {
  const n = nodes.length;
  const tints = new Int32Array(n);
  const glow = new Float32Array(n);

  let maxDegree = 0;
  for (const node of nodes) {
    const d = node.inCount + node.outCount;
    if (d > maxDegree) maxDegree = d;
  }
  const denom = maxDegree > 0 ? maxDegree : 1;

  for (let i = 0; i < n; i++) {
    const node = nodes[i];
    const degree = node.inCount + node.outCount;
    const t = degree / denom;
    tints[i] = sampleRamp(t);
    // Glow 1.0 baseline, up to ~1.8 for the most connected tables.
    glow[i] = 1 + 0.8 * t;
  }
  return { tints, glow };
}
