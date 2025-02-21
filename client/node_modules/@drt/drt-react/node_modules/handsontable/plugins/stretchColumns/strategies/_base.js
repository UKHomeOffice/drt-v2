"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
/**
 * @typedef StretchStrategyCalcArgs
 * @property {number} viewportWidth The width of the viewport.
 */
/**
 * The base strategy stretching strategy to extend from.
 *
 * @private
 * @class StretchStrategy
 */
class StretchStrategy {
  constructor(overwriteColumnWidthFn) {
    /**
     * The width of the viewport.
     *
     * @type {number}
     */
    _defineProperty(this, "viewportWidth", void 0);
    /**
     * The function to overwrite the column width.
     *
     * @type {function(number, number): number | undefined}
     */
    _defineProperty(this, "overwriteColumnWidthFn", void 0);
    /**
     * The map that stores the base column widths.
     *
     * @type {Map<number, number>}
     */
    _defineProperty(this, "baseWidths", new Map());
    /**
     * The map that stores the calculated, stretched column widths.
     *
     * @type {Map<number, number>}
     */
    _defineProperty(this, "stretchedWidths", new Map());
    this.overwriteColumnWidthFn = overwriteColumnWidthFn;
  }

  /**
   * Prepares the strategy for the calculation.
   *
   * @param {StretchStrategyCalcArgs} calcArgs The calculation arguments.
   */
  prepare(_ref) {
    let {
      viewportWidth
    } = _ref;
    this.viewportWidth = viewportWidth;
    this.baseWidths.clear();
    this.stretchedWidths.clear();
  }

  /**
   * Sets the base widths of the columns with which the strategy will work with.
   *
   * @param {number} columnVisualIndex The visual index of the column.
   * @param {number} columnWidth The width of the column.
   */
  setColumnBaseWidth(columnVisualIndex, columnWidth) {
    this.baseWidths.set(columnVisualIndex, columnWidth);
  }

  /**
   * Calculates the width of the column.
   */
  calculate() {}

  /**
   * Gets the calculated stretched column widths.
   *
   * @returns {Array<number[]>}
   */
  getWidths() {
    return Array.from(this.stretchedWidths);
  }
}
exports.StretchStrategy = StretchStrategy;