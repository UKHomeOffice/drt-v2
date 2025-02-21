"use strict";

exports.__esModule = true;
const command = exports.command = {
  name: 'extendCellsSelectionToRows',
  callback(hot) {
    const {
      highlight,
      from,
      to
    } = hot.getSelectedRangeLast();
    if (hot.selection.isSelectedByColumnHeader()) {
      hot.selection.selectAll(true, true);
    } else {
      hot.selectRows(from.row, to.row, highlight);
    }
  }
};