"use strict";

exports.__esModule = true;
exports.cornerHeaderScrollStrategy = cornerHeaderScrollStrategy;
/**
 * Scroll strategy for corner header selection.
 *
 * @returns {function(): function(CellCoords): void}
 */
function cornerHeaderScrollStrategy() {
  return () => {
    // do not scroll the viewport when the corner is clicked
  };
}