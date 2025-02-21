"use strict";

exports.__esModule = true;
const command = exports.command = {
  name: 'moveCellSelectionToMostInlineEnd',
  callback(hot) {
    const {
      selection,
      columnIndexMapper
    } = hot;
    selection.setRangeStart(hot._createCellCoords(hot.getSelectedRangeLast().highlight.row, columnIndexMapper.getNearestNotHiddenIndex(hot.countCols() - 1, -1)));
  }
};