import "core-js/modules/es.error.cause.js";
import "core-js/modules/es.array.push.js";
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
import { ViewportBaseCalculator } from "./viewportBase.mjs";
export const DEFAULT_WIDTH = 50;

/**
 * @typedef {object} ViewportColumnsCalculatorOptions
 * @property {Map<string, ViewportBaseCalculator>} calculationTypes The calculation types to be performed.
 * @property {number} viewportWidth Width of the viewport.
 * @property {number} scrollOffset Current horizontal scroll position of the viewport.
 * @property {number} totalColumns Total number of columns.
 * @property {Function} columnWidthFn Function that returns the width of the column at a given index (in px).
 * @property {Function} overrideFn Function that allows to adjust the `startRow` and `endRow` parameters.
 * @property {string} inlineStartOffset Inline-start offset of the parent container.
 */
/**
 * Calculates indexes of columns to render OR columns that are visible OR partially visible in the viewport.
 *
 * @class ViewportColumnsCalculator
 */
export class ViewportColumnsCalculator extends ViewportBaseCalculator {
  /**
   * @param {ViewportColumnsCalculatorOptions} options Object with all options specified for column viewport calculation.
   */
  constructor(_ref) {
    let {
      calculationTypes,
      viewportWidth,
      scrollOffset,
      totalColumns,
      columnWidthFn,
      overrideFn,
      inlineStartOffset
    } = _ref;
    super(calculationTypes);
    _defineProperty(this, "viewportWidth", 0);
    _defineProperty(this, "scrollOffset", 0);
    _defineProperty(this, "zeroBasedScrollOffset", 0);
    _defineProperty(this, "totalColumns", 0);
    _defineProperty(this, "columnWidthFn", null);
    _defineProperty(this, "columnWidth", 0);
    _defineProperty(this, "overrideFn", null);
    _defineProperty(this, "inlineStartOffset", 0);
    _defineProperty(this, "totalCalculatedWidth", 0);
    _defineProperty(this, "startPositions", []);
    _defineProperty(this, "needReverse", true);
    this.viewportWidth = viewportWidth;
    this.scrollOffset = scrollOffset;
    this.zeroBasedScrollOffset = Math.max(scrollOffset, 0);
    this.totalColumns = totalColumns;
    this.columnWidthFn = columnWidthFn;
    this.overrideFn = overrideFn;
    this.inlineStartOffset = inlineStartOffset;
    this.calculate();
  }

  /**
   * Calculates viewport.
   */
  calculate() {
    this._initialize(this);
    for (let column = 0; column < this.totalColumns; column++) {
      this.columnWidth = this.getColumnWidth(column);
      this._process(column, this);
      this.startPositions.push(this.totalCalculatedWidth);
      this.totalCalculatedWidth += this.columnWidth;
      if (this.totalCalculatedWidth >= this.zeroBasedScrollOffset + this.viewportWidth) {
        this.needReverse = false;
        break;
      }
    }
    this._finalize(this);
  }

  /**
   * Gets the column width at the specified column index.
   *
   * @param {number} column Column index.
   * @returns {number}
   */
  getColumnWidth(column) {
    const width = this.columnWidthFn(column);
    if (isNaN(width)) {
      return DEFAULT_WIDTH;
    }
    return width;
  }
}