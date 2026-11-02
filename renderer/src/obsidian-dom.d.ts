/*
 * Ambient declarations for the Obsidian DOM extensions that the vendored
 * renderer (vendor/, from advanced_graph_view) calls on HTMLElement.
 *
 * Obsidian injects these onto HTMLElement.prototype at runtime; outside
 * Obsidian they are provided by `src/obsidianDomShim.ts`, which must be
 * installed before the renderer module loads. These declarations give the
 * compiler the same surface so `tsc -noEmit` typechecks the sources.
 */
interface CreateElOptions {
  cls?: string | string[];
  text?: string;
  attr?: Record<string, string>;
}

interface HTMLElement {
  createEl<K extends keyof HTMLElementTagNameMap>(
    tag: K,
    opts?: CreateElOptions
  ): HTMLElementTagNameMap[K];
  createDiv(opts?: CreateElOptions): HTMLDivElement;
  createSpan(opts?: CreateElOptions): HTMLSpanElement;
  empty(): void;
  setText(text: string): void;
}
