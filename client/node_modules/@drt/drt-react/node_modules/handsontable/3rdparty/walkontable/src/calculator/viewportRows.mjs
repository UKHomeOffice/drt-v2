import "core-js/modules/es.error.cause.js";
import "core-js/modules/es.array.push.js";
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
import { ViewportBaseCalculator } from "./viewportBase.mjs";
/**
 * @typedef {object} ViewportRowsCalculatorOptions
 * @property {Map<string, ViewportBaseCalculator>} calculationTypes The calculation types to be performed.
 * @property {number} viewportHeight Height of the viewport.
 * @property {number} scrollOffset Current vertical scroll position of the viewport.
 * @property {number} totalRows Total number of rows.
 * @property {Function} rowHeightFn Function that returns the height of the row at a given index (in px).
 * @property {Function} overrideFn Function that allows to adjust the `startRow` and `endRow` parameters.
 * @property {number} horizontalScrollbarHeight The scrollbar height.
 */
/**
 * Calculates indexes of rows to render OR rows that are visible OR partially visible in the viewport.
 *
 * @class ViewportRowsCalculator
 */
export class ViewportRowsCalculator extends ViewportBaseCalculator {
  /**
   * @param {ViewportRowsCalculatorOptions} options Object with all options specified for row viewport calculation.
   */
  constructor(_ref) {
    let {
      calculationTypes,
      viewportHeight,
      scrollOffset,
      totalRows,
      defaultRowHeight,
      rowHeightFn,
      overrideFn,
      horizontalScrollbarHeight
    } = _ref;
    super(calculationTypes);
    _defineProperty(this, "viewportHeight", 0);
    _defineProperty(this, "scrollOffset", 0);
    _defineProperty(this, "zeroBasedScrollOffset", 0);
    _defineProperty(this, "totalRows", 0);
    _defineProperty(this, "rowHeightFn", null);
    _defineProperty(this, "rowHeight", 0);
    _defineProperty(this, "overrideFn", null);
    _defineProperty(this, "horizontalScrollbarHeight", 0);
    _defineProperty(this, "innerViewportHeight", 0);
    _defineProperty(this, "totalCalculatedHeight", 0);
    _defineProperty(this, "startPositions", []);
    _defineProperty(this, "needReverse", true);
    this.defaultHeight = defaultRowHeight;
    this.viewportHeight = viewportHeight;
    this.scrollOffset = scrollOffset;
    this.zeroBasedScrollOffset = Math.max(scrollOffset, 0);
    this.totalRows = totalRows;
    this.rowHeightFn = rowHeightFn;
    this.overrideFn = overrideFn;
    this.horizontalScrollbarHeight = horizontalScrollbarHeight !== null && horizontalScrollbarHeight !== void 0 ? horizontalScrollbarHeight : 0;
    this.innerViewportHeight = this.zeroBasedScrollOffset + this.viewportHeight - this.horizontalScrollbarHeight;
    this.calculate();
  }

  /**
   * Calculates viewport.
   */
  calculate() {
    this._initialize(this);
    for (let row = 0; row < this.totalRows; row++) {
      this.rowHeight = this.getRowHeight(row);
      this._process(row, this);
      this.startPositions.push(this.totalCalculatedHeight);
      this.totalCalculatedHeight += this.rowHeight;
      if (this.totalCalculatedHeight >= this.innerViewportHeight) {
        this.needReverse = false;
        break;
      }
    }
    this._finalize(this);
  }

  /**
   * Gets the row height at the specified row index.
   *
   * @param {number} row Row index.
   * @returns {number}
   */
  getRowHeight(row) {
    const rowHeight = this.rowHeightFn(row);
    if (isNaN(rowHeight)) {
      return this.defaultHeight;
    }
    return rowHeight;
  }
}