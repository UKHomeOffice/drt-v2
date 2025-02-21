"use strict";

exports.__esModule = true;
const command = exports.command = {
  name: 'moveCellSelectionRight',
  callback(hot) {
    hot.selection.transformStart(0, hot.getDirectionFactor());
  }
};