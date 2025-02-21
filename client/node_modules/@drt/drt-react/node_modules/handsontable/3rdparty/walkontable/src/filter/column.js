"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
/**
 * @class ColumnFilter
 */
class ColumnFilter {
  /**
   * @param {number} offset The scroll horizontal offset.
   * @param {number} total The total width of the table.
   * @param {number} countTH The number of rendered row headers.
   */
  constructor(offset, total, countTH) {
    /**
     * @type {number}
     */
    _defineProperty(this, "offset", void 0);
    /**
     * @type {number}
     */
    _defineProperty(this, "total", void 0);
    /**
     * @type {number}
     */
    _defineProperty(this, "countTH", void 0);
    this.offset = offset;
    this.total = total;
    this.countTH = countTH;
  }

  /**
   * @param {number} index The visual column index.
   * @returns {number}
   */
  offsetted(index) {
    return index + this.offset;
  }

  /**
   * @param {number} index The visual column index.
   * @returns {number}
   */
  unOffsetted(index) {
    return index - this.offset;
  }

  /**
   * @param {number} index The visual column index.
   * @returns {number}
   */
  renderedToSource(index) {
    return this.offsetted(index);
  }

  /**
   * @param {number} index The visual column index.
   * @returns {number}
   */
  sourceToRendered(index) {
    return this.unOffsetted(index);
  }

  /**
   * @param {number} index The visual column index.
   * @returns {number}
   */
  offsettedTH(index) {
    return index - this.countTH;
  }

  /**
   * @param {number} index The visual column index.
   * @returns {number}
   */
  unOffsettedTH(index) {
    return index + this.countTH;
  }

  /**
   * @param {number} index The visual column index.
   * @returns {number}
   */
  visibleRowHeadedColumnToSourceColumn(index) {
    return this.renderedToSource(this.offsettedTH(index));
  }

  /**
   * @param {number} index The visual column index.
   * @returns {number}
   */
  sourceColumnToVisibleRowHeadedColumn(index) {
    return this.unOffsettedTH(this.sourceToRendered(index));
  }
}
var _default = exports.default = ColumnFilter;