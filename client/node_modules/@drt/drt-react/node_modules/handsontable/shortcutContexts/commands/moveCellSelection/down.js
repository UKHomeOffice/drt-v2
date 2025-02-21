"use strict";

exports.__esModule = true;
const command = exports.command = {
  name: 'moveCellSelectionDown',
  callback(_ref) {
    let {
      selection
    } = _ref;
    selection.transformStart(1, 0);
  }
};