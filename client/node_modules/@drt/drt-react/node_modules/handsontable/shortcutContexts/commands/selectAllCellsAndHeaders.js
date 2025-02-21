"use strict";

exports.__esModule = true;
const command = exports.command = {
  name: 'selectAllCellsAndHeaders',
  callback(hot) {
    hot.selection.selectAll(true, true, {
      disableHeadersHighlight: false
    });
  }
};