// client/src/test/resources/dom-setup.js
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

function setupWithLegacy() {
  // Legacy API: jsdomMod.jsdom(html, options) -> Document
  if (typeof jsdomMod.jsdom !== "function") return false;

  const document = jsdomMod.jsdom("<!doctype html><html><body></body></html>", {
    url: "http://localhost/",
  });
  const window = document.defaultView;

  global.window = window;
  global.document = document;
  global.navigator = window.navigator;
  return true;
}

if (!setupWithModern() && !setupWithLegacy()) {
  console.error("[dom-setup] jsdom module keys:", Object.keys(jsdomMod || {}));
  throw new TypeError("Could not obtain a JSDOM constructor or legacy jsdom() function");
}

// Fill common globals used by libs like Handsontable
global.HTMLElement = global.window.HTMLElement;
global.Node = global.window.Node;
global.getComputedStyle = global.window.getComputedStyle;
global.MutationObserver = global.window.MutationObserver;

try {
  global.ResizeObserver =
    global.ResizeObserver || require("resize-observer-polyfill").default;
} catch (_) {}

if (!global.requestAnimationFrame) global.requestAnimationFrame = (cb) => setTimeout(cb, 0);
if (!global.cancelAnimationFrame) global.cancelAnimationFrame = (id) => clearTimeout(id);

console.log("[dom-setup] document ready:", !!global.document);
