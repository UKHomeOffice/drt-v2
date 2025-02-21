"use strict";

exports.__esModule = true;
exports.focusScrollStrategy = focusScrollStrategy;
/**
 * Scroll strategy for changed the focus position of the selection.
 *
 * @param {Core} hot Handsontable instance.
 * @returns {function(): function(CellCoords): void}
 */
function focusScrollStrategy(hot) {
  return cellCoords => {
    hot.scrollViewportTo(cellCoords.toObject());
  };
}