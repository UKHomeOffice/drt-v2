"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
require("core-js/modules/es.array.at.js");
require("core-js/modules/es.string.at-alternative.js");
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
/**
 * @class PartiallyVisibleColumnsCalculationType
 */
class PartiallyVisibleColumnsCalculationType {
  constructor() {
    /**
     * Total number of partially visible columns in the viewport.
     *
     * @type {number}
     */
    _defineProperty(this, "count", 0);
    /**
     * The column index of the first partially visible column in the viewport.
     *
     * @type {number|null}
     */
    _defineProperty(this, "startColumn", null);
    /**
     * The column index of the last partially visible column in the viewport.
     *
     * @type {number|null}
     */
    _defineProperty(this, "endColumn", null);
    /**
     * Position of the first partially visible column (in px).
     *
     * @type {number|null}
     */
    _defineProperty(this, "startPosition", null);
    /**
     * Determines if the viewport is visible in the trimming container.
     *
     * @type {boolean}
     */
    _defineProperty(this, "isVisibleInTrimmingContainer", false);
  }
  /**
   * Initializes the calculation.
   */
  initialize() {}

  /**
   * Processes the column.
   *
   * @param {number} column The column index.
   * @param {ViewportColumnsCalculator} viewportCalculator The viewport calculator object.
   */
  process(column, viewportCalculator) {
    const {
      totalCalculatedWidth,
      zeroBasedScrollOffset,
      viewportWidth
    } = viewportCalculator;
    if (totalCalculatedWidth <= zeroBasedScrollOffset) {
      this.startColumn = column;
    }
    const compensatedViewportWidth = zeroBasedScrollOffset > 0 ? viewportWidth + 1 : viewportWidth;
    if (totalCalculatedWidth >= zeroBasedScrollOffset && totalCalculatedWidth <= zeroBasedScrollOffset + compensatedViewportWidth) {
      if (this.startColumn === null || this.startColumn === undefined) {
        this.startColumn = column;
      }
    }
    this.endColumn = column;
  }

  /**
   * Finalizes the calculation.
   *
   * @param {ViewportColumnsCalculator} viewportCalculator The viewport calculator object.
   */
  finalize(viewportCalculator) {
    var _startPositions$this$;
    const {
      scrollOffset,
      viewportWidth,
      inlineStartOffset,
      zeroBasedScrollOffset,
      totalColumns,
      needReverse,
      startPositions,
      columnWidth
    } = viewportCalculator;

    // If the estimation has reached the last column and there is still some space available in the viewport,
    // we need to render in reverse in order to fill the whole viewport with columns
    if (this.endColumn === totalColumns - 1 && needReverse) {
      this.startColumn = this.endColumn;
      while (this.startColumn > 0) {
        const calculatedViewportWidth = startPositions[this.endColumn] + columnWidth - startPositions[this.startColumn - 1];
        this.startColumn -= 1;
        if (calculatedViewportWidth > viewportWidth) {
          break;
        }
      }
    }
    this.startPosition = (_startPositions$this$ = startPositions[this.startColumn]) !== null && _startPositions$this$ !== void 0 ? _startPositions$this$ : null;
    const compensatedViewportWidth = zeroBasedScrollOffset > 0 ? viewportWidth + 1 : viewportWidth;
    const mostRightScrollOffset = scrollOffset + viewportWidth - compensatedViewportWidth;
    if (
    // the table is to the left of the viewport
    mostRightScrollOffset < -1 * inlineStartOffset || scrollOffset > startPositions.at(-1) + columnWidth ||
    // the table is to the right of the viewport
    -1 * scrollOffset - viewportWidth > 0) {
      this.isVisibleInTrimmingContainer = false;
    } else {
      this.isVisibleInTrimmingContainer = true;
    }
    if (totalColumns < this.endColumn) {
      this.endColumn = totalColumns - 1;
    }
    if (this.startColumn !== null) {
      this.count = this.endColumn - this.startColumn + 1;
    }
  }
}
exports.PartiallyVisibleColumnsCalculationType = PartiallyVisibleColumnsCalculationType;