"use strict";

exports.__esModule = true;
const command = exports.command = {
  name: 'selectAllCells',
  callback(hot) {
    hot.selection.selectAll(true, true, {
      disableHeadersHighlight: true
    });
  }
};