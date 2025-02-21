"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
/**
 * Holder for current and next size (count of rendered and to render DOM elements) and offset.
 *
 * @class {ViewSize}
 */
class ViewSize {
  constructor() {
    /**
     * Current size of the rendered DOM elements.
     *
     * @type {number}
     */
    _defineProperty(this, "currentSize", 0);
    /**
     * Next size of the rendered DOM elements which should be fulfilled.
     *
     * @type {number}
     */
    _defineProperty(this, "nextSize", 0);
    /**
     * Current offset.
     *
     * @type {number}
     */
    _defineProperty(this, "currentOffset", 0);
    /**
     * Next offset.
     *
     * @type {number}
     */
    _defineProperty(this, "nextOffset", 0);
  }
  /**
   * Sets new size of the rendered DOM elements.
   *
   * @param {number} size The size.
   */
  setSize(size) {
    this.currentSize = this.nextSize;
    this.nextSize = size;
  }

  /**
   * Sets new offset.
   *
   * @param {number} offset The offset.
   */
  setOffset(offset) {
    this.currentOffset = this.nextOffset;
    this.nextOffset = offset;
  }
}
exports.ViewSize = ViewSize;