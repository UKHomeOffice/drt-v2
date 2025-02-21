"use strict";

exports.__esModule = true;
exports.noncontiguousScrollStrategy = noncontiguousScrollStrategy;
/**
 * Scroll strategy for non-contiguous selections.
 *
 * @param {Core} hot Handsontable instance.
 * @returns {function(): function(CellCoords): void}
 */
function noncontiguousScrollStrategy(hot) {
  return cellCoords => {
    hot.scrollViewportTo(cellCoords.toObject());
  };
}