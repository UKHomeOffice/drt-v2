"use strict";

exports.__esModule = true;
const command = exports.command = {
  name: 'extendCellsSelectionUp',
  callback(hot) {
    const {
      highlight
    } = hot.getSelectedRangeLast();
    if (!hot.selection.isSelectedByColumnHeader() && !hot.selection.isSelectedByCorner() && (highlight.isCell() || highlight.isHeader() && hot.selection.isSelectedByRowHeader())) {
      hot.selection.transformEnd(-1, 0);
    }
  }
};