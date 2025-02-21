"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
var _partiallyVisibleRows = require("./partiallyVisibleRows");
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
/**
 * @class RenderedRowsCalculationType
 */
class RenderedRowsCalculationType extends _partiallyVisibleRows.PartiallyVisibleRowsCalculationType {
  constructor() {
    super(...arguments);
    /**
     * The property holds the offset applied in the `overrideFn` function to the `startColumn` value.
     *
     * @type {number}
     */
    _defineProperty(this, "rowStartOffset", 0);
    /**
     * The property holds the offset applied in the `overrideFn` function to the `endColumn` value.
     *
     * @type {number}
     */
    _defineProperty(this, "rowEndOffset", 0);
  }
  /**
   * Finalizes the calculation.
   *
   * @param {ViewportRowsCalculator} viewportCalculator The viewport calculator object.
   */
  finalize(viewportCalculator) {
    var _startPositions$this$;
    super.finalize(viewportCalculator);
    const {
      overrideFn,
      totalRows,
      startPositions
    } = viewportCalculator;
    if (this.startRow !== null && typeof overrideFn === 'function') {
      const startRow = this.startRow;
      const endRow = this.endRow;
      overrideFn(this);
      this.rowStartOffset = startRow - this.startRow;
      this.rowEndOffset = this.endRow - endRow;
    }
    if (this.startRow < 0) {
      this.startRow = 0;
    }
    this.startPosition = (_startPositions$this$ = startPositions[this.startRow]) !== null && _startPositions$this$ !== void 0 ? _startPositions$this$ : null;
    if (totalRows < this.endRow) {
      this.endRow = totalRows - 1;
    }
    if (this.startRow !== null) {
      this.count = this.endRow - this.startRow + 1;
    }
  }
}
exports.RenderedRowsCalculationType = RenderedRowsCalculationType;