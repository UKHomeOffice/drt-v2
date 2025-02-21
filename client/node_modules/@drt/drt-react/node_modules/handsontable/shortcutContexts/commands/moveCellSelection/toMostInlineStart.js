"use strict";

exports.__esModule = true;
const command = exports.command = {
  name: 'moveCellSelectionToMostInlineStart',
  callback(hot) {
    const {
      selection,
      columnIndexMapper
    } = hot;
    const fixedColumns = parseInt(hot.getSettings().fixedColumnsStart, 10);
    const row = hot.getSelectedRangeLast().highlight.row;
    const column = columnIndexMapper.getNearestNotHiddenIndex(fixedColumns, 1);
    selection.setRangeStart(hot._createCellCoords(row, column));
  }
};