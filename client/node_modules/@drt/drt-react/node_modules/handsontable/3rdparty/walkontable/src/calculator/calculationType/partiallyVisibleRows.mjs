import "core-js/modules/es.error.cause.js";
import "core-js/modules/es.array.at.js";
import "core-js/modules/es.string.at-alternative.js";
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
/**
 * @class PartiallyVisibleRowsCalculationType
 */
export class PartiallyVisibleRowsCalculationType {
  constructor() {
    /**
     * Total number of partially visible rows in the viewport.
     *
     * @type {number}
     */
    _defineProperty(this, "count", 0);
    /**
     * The row index of the first partially visible row in the viewport.
     *
     * @type {number|null}
     */
    _defineProperty(this, "startRow", null);
    /**
     * The row index of the last partially visible row in the viewport.
     *
     * @type {number|null}
     */
    _defineProperty(this, "endRow", null);
    /**
     * Position of the first partially visible row (in px).
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
   * Processes the row.
   *
   * @param {number} row The row index.
   * @param {ViewportRowsCalculator} viewportCalculator The viewport calculator object.
   */
  process(row, viewportCalculator) {
    const {
      totalCalculatedHeight,
      zeroBasedScrollOffset,
      innerViewportHeight
    } = viewportCalculator;
    if (totalCalculatedHeight <= zeroBasedScrollOffset) {
      this.startRow = row;
    }
    if (totalCalculatedHeight >= zeroBasedScrollOffset && totalCalculatedHeight <= innerViewportHeight) {
      if (this.startRow === null) {
        this.startRow = row;
      }
    }
    this.endRow = row;
  }

  /**
   * Finalizes the calculation.
   *
   * @param {ViewportRowsCalculator} viewportCalculator The viewport calculator object.
   */
  finalize(viewportCalculator) {
    var _startPositions$this$;
    const {
      scrollOffset,
      viewportHeight,
      horizontalScrollbarHeight,
      totalRows,
      needReverse,
      startPositions,
      rowHeight
    } = viewportCalculator;

    // If the estimation has reached the last row and there is still some space available in the viewport,
    // we need to render in reverse in order to fill the whole viewport with rows
    if (this.endRow === totalRows - 1 && needReverse) {
      this.startRow = this.endRow;
      while (this.startRow > 0) {
        const calculatedViewportHeight = startPositions[this.endRow] + rowHeight - startPositions[this.startRow - 1];
        this.startRow -= 1;
        if (calculatedViewportHeight >= viewportHeight - horizontalScrollbarHeight) {
          break;
        }
      }
    }
    this.startPosition = (_startPositions$this$ = startPositions[this.startRow]) !== null && _startPositions$this$ !== void 0 ? _startPositions$this$ : null;
    const mostBottomScrollOffset = scrollOffset + viewportHeight - horizontalScrollbarHeight;
    if (mostBottomScrollOffset < 0 || scrollOffset > startPositions.at(-1) + rowHeight) {
      this.isVisibleInTrimmingContainer = false;
    } else {
      this.isVisibleInTrimmingContainer = true;
    }
    if (totalRows < this.endRow) {
      this.endRow = totalRows - 1;
    }
    if (this.startRow !== null) {
      this.count = this.endRow - this.startRow + 1;
    }
  }
}