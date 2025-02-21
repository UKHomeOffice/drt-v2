"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
/**
 * @typedef {object} RenderedAllColumnsCalculatorOptions
 * @property {number} totalColumns Total number of columns.
 */
/**
 * Holds all calculations needed to perform the rendering of all columns.
 *
 * @class RenderedAllColumnsCalculationType
 */
class RenderedAllColumnsCalculationType {
  constructor() {
    /**
     * Number of rendered/visible columns.
     *
     * @type {number}
     */
    _defineProperty(this, "count", 0);
    /**
     * Index of the first rendered/visible column.
     *
     * @type {number}
     */
    _defineProperty(this, "startColumn", 0);
    /**
     * Index of the last rendered/visible column.
     *
     * @type {number}
     */
    _defineProperty(this, "endColumn", 0);
    /**
     * Position of the first rendered/visible column (in px).
     *
     * @type {number}
     */
    _defineProperty(this, "startPosition", 0);
    /**
     * Determines if the viewport is visible in the trimming container.
     *
     * @type {boolean}
     */
    _defineProperty(this, "isVisibleInTrimmingContainer", true);
  }
  /**
   * Initializes the calculation.
   *
   * @param {ViewportColumnsCalculator} viewportCalculator The viewport calculator object.
   */
  initialize(_ref) {
    let {
      totalColumns
    } = _ref;
    this.count = totalColumns;
    this.endColumn = this.count - 1;
  }

  /**
   * Processes the column.
   */
  process() {}

  /**
   * Finalizes the calculation.
   */
  finalize() {}
}
exports.RenderedAllColumnsCalculationType = RenderedAllColumnsCalculationType;