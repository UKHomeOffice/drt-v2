"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
/**
 * Factory for newly created DOM elements.
 *
 * @class {NodesPool}
 */
class NodesPool {
  constructor(nodeType) {
    /**
     * Node type to generate (e.g. 'TH', 'TD').
     *
     * @type {string}
     */
    _defineProperty(this, "nodeType", void 0);
    /**
     * The holder for all created DOM nodes (THs, TDs).
     *
     * @type {Map<string, HTMLElement>}
     */
    _defineProperty(this, "pool", new Map());
    this.nodeType = nodeType.toUpperCase();
  }

  /**
   * Set document owner for this instance.
   *
   * @param {Document} rootDocument The document window owner.
   */
  setRootDocument(rootDocument) {
    this.rootDocument = rootDocument;
  }

  /**
   * Obtains an element.
   *
   * @param {number} rowIndex The row index.
   * @param {number} [columnIndex] The column index.
   * @returns {HTMLElement}
   */
  obtain(rowIndex, columnIndex) {
    const hasColumnIndex = typeof columnIndex === 'number';
    const key = hasColumnIndex ? `${rowIndex}x${columnIndex}` : rowIndex.toString();
    if (this.pool.has(key)) {
      return this.pool.get(key);
    }
    const node = this.rootDocument.createElement(this.nodeType);
    this.pool.set(key, node);
    return node;
  }
}
exports.NodesPool = NodesPool;