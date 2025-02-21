"use strict";

exports.__esModule = true;
exports.columnHeaderScrollStrategy = columnHeaderScrollStrategy;
/**
 * Scroll strategy for column header selection.
 *
 * @param {Core} hot Handsontable instance.
 * @returns {function(): function(CellCoords): void}
 */
function columnHeaderScrollStrategy(hot) {
  return _ref => {
    let {
      col
    } = _ref;
    hot.scrollViewportTo({
      col
    });
  };
}