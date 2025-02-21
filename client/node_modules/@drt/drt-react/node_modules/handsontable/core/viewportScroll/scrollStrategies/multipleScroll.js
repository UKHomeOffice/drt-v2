"use strict";

exports.__esModule = true;
exports.multipleScrollStrategy = multipleScrollStrategy;
/**
 * Scroll strategy for multiple selections.
 *
 * @param {Core} hot Handsontable instance.
 * @returns {function(): function(CellCoords): void}
 */
function multipleScrollStrategy(hot) {
  return cellCoords => {
    hot.scrollViewportTo(cellCoords.toObject());
  };
}