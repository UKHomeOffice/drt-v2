"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
var _partiallyVisibleColumns = require("./partiallyVisibleColumns");
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
/**
 * @class RenderedColumnsCalculationType
 */
class RenderedColumnsCalculationType extends _partiallyVisibleColumns.PartiallyVisibleColumnsCalculationType {
  constructor() {
    super(...arguments);
    /**
     * The property holds the offset applied in the `overrideFn` function to the `startColumn` value.
     *
     * @type {number}
     */
    _defineProperty(this, "columnStartOffset", 0);
    /**
     * The property holds the offset applied in the `overrideFn` function to the `endColumn` value.
     *
     * @type {number}
     */
    _defineProperty(this, "columnEndOffset", 0);
  }
  /**
   * Finalizes the calculation.
   *
   * @param {ViewportColumnsCalculator} viewportCalculator The viewport calculator object.
   */
  finalize(viewportCalculator) {
    var _startPositions$this$;
    super.finalize(viewportCalculator);
    const {
      overrideFn,
      totalColumns,
      startPositions
    } = viewportCalculator;
    if (this.startColumn !== null && typeof overrideFn === 'function') {
      const startColumn = this.startColumn;
      const endColumn = this.endColumn;
      overrideFn(this);
      this.columnStartOffset = startColumn - this.startColumn;
      this.columnEndOffset = this.endColumn - endColumn;
    }
    if (this.startColumn < 0) {
      this.startColumn = 0;
    }
    this.startPosition = (_startPositions$this$ = startPositions[this.startColumn]) !== null && _startPositions$this$ !== void 0 ? _startPositions$this$ : null;
    if (totalColumns < this.endColumn) {
      this.endColumn = totalColumns - 1;
    }
    if (this.startColumn !== null) {
      this.count = this.endColumn - this.startColumn + 1;
    }
  }
}
exports.RenderedColumnsCalculationType = RenderedColumnsCalculationType;