"use strict";

exports.__esModule = true;
const command = exports.command = {
  name: 'moveCellSelectionToMostLeft',
  callback(hot) {
    const {
      selection,
      columnIndexMapper
    } = hot;
    const row = hot.getSelectedRangeLast().highlight.row;
    let column = columnIndexMapper.getNearestNotHiddenIndex(...(hot.isRtl() ? [hot.countCols() - 1, -1] : [0, 1]));
    if (column === null) {
      column = hot.isRtl() ? -1 : -hot.countRowHeaders();
    }
    selection.setRangeStart(hot._createCellCoords(row, column));
  }
};