/* eslint-disable */
let jsdomMod = require("jsdom");

function setupWithModern() {
  const JSDOM = jsdomMod.JSDOM || jsdomMod.default;
  if (!JSDOM) return false;
  const dom = new JSDOM("<!doctype html><html><body></body></html>", { url: "http://localhost/" });

  global.window = dom.window;
  global.document = dom.window.document;
  global.navigator = dom.window.navigator;

  return true;
}

if (!setupWithModern()) {
  console.error("[dom-setup] jsdom module keys:", Object.keys(jsdomMod || {}));
  throw new TypeError("Could not obtain a JSDOM constructor or legacy jsdom() function");
}

// Fill common globals used by libs like Handsontable
global.HTMLElement = global.window.HTMLElement;
global.Element = global.window.Element;
global.Node = global.window.Node;
global.Text = global.window.Text;
global.HTMLInputElement = global.window.HTMLInputElement;
global.getComputedStyle = global.window.getComputedStyle;
global.MutationObserver = global.window.MutationObserver;

try {
  const ResizeObserverPolyfill = require("resize-observer-polyfill");
  global.ResizeObserver =
    global.ResizeObserver || ResizeObserverPolyfill.default || ResizeObserverPolyfill;
} catch (_) {}

if (!global.requestAnimationFrame) global.requestAnimationFrame = (cb) => setTimeout(cb, 0);
if (!global.cancelAnimationFrame) global.cancelAnimationFrame = (id) => clearTimeout(id);
global.window.requestAnimationFrame = global.requestAnimationFrame;
global.window.cancelAnimationFrame = global.cancelAnimationFrame;

// jsdom has no canvas; give Chart.js a no-op 2D context so mounted charts render.
global.window.HTMLCanvasElement.prototype.getContext = function () {
  const noop = () => {};
  return new Proxy({ canvas: this, measureText: () => ({ width: 0 }), getLineDash: () => [] }, {
    get: (target, property) => (property in target ? target[property] : noop),
  });
};

console.log("[dom-setup] document ready:", !!global.document);
