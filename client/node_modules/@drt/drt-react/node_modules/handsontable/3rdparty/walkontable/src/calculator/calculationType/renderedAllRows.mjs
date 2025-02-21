import "core-js/modules/es.error.cause.js";
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
/**
 * @typedef {object} RenderedAllRowsCalculatorOptions
 * @property {number} totalRows Total number of rows.
 */
/**
 * Holds all calculations needed to perform the rendering of all rows.
 *
 * @class RenderedAllRowsCalculationType
 */
export class RenderedAllRowsCalculationType {
  constructor() {
    /**
     * Number of rendered/visible rows.
     *
     * @type {number}
     */
    _defineProperty(this, "count", 0);
    /**
     * Index of the first rendered/visible row.
     *
     * @type {number}
     */
    _defineProperty(this, "startRow", 0);
    /**
     * Index of the last rendered/visible row.
     *
     * @type {number}
     */
    _defineProperty(this, "endRow", 0);
    /**
     * Position of the first rendered/visible row (in px).
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
   * @param {ViewportRowsCalculator} viewportCalculator The viewport calculator object.
   */
  initialize(_ref) {
    let {
      totalRows
    } = _ref;
    this.count = totalRows;
    this.endRow = this.count - 1;
  }

  /**
   * Processes the row.
   */
  process() {}

  /**
   * Finalizes the calculation.
   */
  finalize() {}
}