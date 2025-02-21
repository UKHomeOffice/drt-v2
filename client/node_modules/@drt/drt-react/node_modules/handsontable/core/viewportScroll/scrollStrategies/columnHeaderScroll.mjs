/**
 * Scroll strategy for column header selection.
 *
 * @param {Core} hot Handsontable instance.
 * @returns {function(): function(CellCoords): void}
 */
export function columnHeaderScrollStrategy(hot) {
  return _ref => {
    let {
      col
    } = _ref;
    hot.scrollViewportTo({
      col
    });
  };
}