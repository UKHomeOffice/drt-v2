"use strict";

exports.__esModule = true;
exports.rowHeaderScrollStrategy = rowHeaderScrollStrategy;
/**
 * Scroll strategy for row header selection.
 *
 * @param {Core} hot Handsontable instance.
 * @returns {function(): function(CellCoords): void}
 */
function rowHeaderScrollStrategy(hot) {
  return _ref => {
    let {
      row
    } = _ref;
    hot.scrollViewportTo({
      row
    });
  };
}