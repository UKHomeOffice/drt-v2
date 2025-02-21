"use strict";

exports.__esModule = true;
const command = exports.command = {
  name: 'moveCellSelectionToMostRight',
  callback(hot) {
    const {
      selection,
      columnIndexMapper
    } = hot;
    const {
      row
    } = hot.getSelectedRangeLast().highlight;
    let column = columnIndexMapper.getNearestNotHiddenIndex(...(hot.isRtl() ? [0, 1] : [hot.countCols() - 1, -1]));
    if (column === null) {
      column = hot.isRtl() ? -hot.countRowHeaders() : -1;
    }
    selection.setRangeStart(hot._createCellCoords(row, column));
  }
};