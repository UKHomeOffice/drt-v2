"use strict";

exports.__esModule = true;
const command = exports.command = {
  name: 'moveCellSelectionLeft',
  callback(hot) {
    hot.selection.transformStart(0, -1 * hot.getDirectionFactor());
  }
};