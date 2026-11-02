/*
 * DBeaver 3D Schema Graph — browser bundle
 * Obsidian DOM shim.
 *
 * The vendored renderer (advanced_graph_view, MIT) calls a few Obsidian DOM
 * extensions that Obsidian injects onto HTMLElement.prototype at runtime:
 *   host.createDiv(), el.createEl(tag, opts), el.empty()
 * Outside Obsidian those do not exist, so we install minimal, standards-based
 * equivalents BEFORE the renderer module is imported. This is the "~10% thin
 * shim" the reuse analysis called for — one small file, no renderer edits.
 */

interface CreateElOptions {
  cls?: string | string[];
  text?: string;
  attr?: Record<string, string>;
}

function applyOptions(el: HTMLElement, opts?: CreateElOptions): HTMLElement {
  if (!opts) return el;
  if (opts.cls) {
    const classes = Array.isArray(opts.cls) ? opts.cls : [opts.cls];
    el.classList.add(...classes.filter(Boolean));
  }
  if (opts.text != null) el.textContent = opts.text;
  if (opts.attr) {
    for (const [k, v] of Object.entries(opts.attr)) el.setAttribute(k, v);
  }
  return el;
}

export function installObsidianDomShim(): void {
  const proto = HTMLElement.prototype as unknown as Record<string, unknown>;

  if (typeof proto.createEl !== "function") {
    proto.createEl = function <K extends keyof HTMLElementTagNameMap>(
      this: HTMLElement,
      tag: K,
      opts?: CreateElOptions
    ): HTMLElement {
      const el = document.createElement(tag);
      this.appendChild(el);
      return applyOptions(el, opts);
    };
  }

  if (typeof proto.createDiv !== "function") {
    proto.createDiv = function (this: HTMLElement, opts?: CreateElOptions): HTMLElement {
      return (this as unknown as { createEl: (t: string, o?: CreateElOptions) => HTMLElement })
        .createEl("div", opts);
    };
  }

  if (typeof proto.createSpan !== "function") {
    proto.createSpan = function (this: HTMLElement, opts?: CreateElOptions): HTMLElement {
      return (this as unknown as { createEl: (t: string, o?: CreateElOptions) => HTMLElement })
        .createEl("span", opts);
    };
  }

  if (typeof proto.empty !== "function") {
    proto.empty = function (this: HTMLElement): void {
      while (this.firstChild) this.removeChild(this.firstChild);
    };
  }

  if (typeof proto.setText !== "function") {
    proto.setText = function (this: HTMLElement, text: string): void {
      this.textContent = text;
    };
  }
}
