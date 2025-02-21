"use strict";

exports.__esModule = true;
const command = exports.command = {
  name: 'moveCellSelectionToMostTop',
  callback(hot) {
    const {
      col
    } = hot.getSelectedRangeLast().highlight;
    let row = hot.rowIndexMapper.getNearestNotHiddenIndex(0, 1);
    if (row === null) {
      row = -hot.countColHeaders();
    }
    hot.selection.setRangeStart(hot._createCellCoords(row, col));
  }
};