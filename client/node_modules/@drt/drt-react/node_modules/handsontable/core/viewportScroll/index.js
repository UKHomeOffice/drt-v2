"use strict";

exports.__esModule = true;
exports.createViewportScroller = createViewportScroller;
var _columnHeaderScroll = require("./scrollStrategies/columnHeaderScroll");
var _cornerHeaderScroll = require("./scrollStrategies/cornerHeaderScroll");
var _focusScroll = require("./scrollStrategies/focusScroll");
var _multipleScroll = require("./scrollStrategies/multipleScroll");
var _noncontiguousScroll = require("./scrollStrategies/noncontiguousScroll");
var _rowHeaderScroll = require("./scrollStrategies/rowHeaderScroll");
var _singleScroll = require("./scrollStrategies/singleScroll");
/**
 * @typedef ViewportScroller
 * @property {function(): void} resume Resumes the viewport scroller.
 * @property {function(): void} suspend Suspends the viewport scroller until the `resume` method is called.
 * @property {function(): void} skipNextScrollCycle Skip the next scroll cycle.
 * @property {function(CellCoords): void} scrollTo Scroll the viewport to a given cell.
 */
/**
 * Installs a viewport scroller module. The module is responsible for scrolling the viewport to a given cell
 * based on the selection type (single cell selection, multiple cells selection, header selection etc.).
 * It's triggered by the selection module via the `afterSetRangeEnd` hook every time the selection changes.
 *
 * @param {Core} hot The Handsontable instance.
 * @returns {ViewportScroller} The viewport scroller module.
 */
function createViewportScroller(hot) {
  const {
    selection
  } = hot;
  let skipNextCall = false;
  let isSuspended = false;
  return {
    resume() {
      isSuspended = false;
    },
    suspend() {
      isSuspended = true;
    },
    skipNextScrollCycle() {
      skipNextCall = true;
    },
    scrollTo(cellCoords) {
      var _scrollStrategy;
      if (skipNextCall || isSuspended) {
        skipNextCall = false;
        return;
      }
      let scrollStrategy;
      if (selection.isFocusSelectionChanged()) {
        scrollStrategy = (0, _focusScroll.focusScrollStrategy)(hot);
      } else if (selection.isSelectedByCorner()) {
        scrollStrategy = (0, _cornerHeaderScroll.cornerHeaderScrollStrategy)(hot);
      } else if (selection.isSelectedByRowHeader()) {
        scrollStrategy = (0, _rowHeaderScroll.rowHeaderScrollStrategy)(hot);
      } else if (selection.isSelectedByColumnHeader()) {
        scrollStrategy = (0, _columnHeaderScroll.columnHeaderScrollStrategy)(hot);
      } else if (selection.getSelectedRange().size() === 1 && selection.isMultiple()) {
        scrollStrategy = (0, _multipleScroll.multipleScrollStrategy)(hot);
      } else if (selection.getSelectedRange().size() === 1 && !selection.isMultiple()) {
        scrollStrategy = (0, _singleScroll.singleScrollStrategy)(hot);
      } else if (selection.getSelectedRange().size() > 1) {
        scrollStrategy = (0, _noncontiguousScroll.noncontiguousScrollStrategy)(hot);
      }
      (_scrollStrategy = scrollStrategy) === null || _scrollStrategy === void 0 || _scrollStrategy(cellCoords);
    }
  };
}