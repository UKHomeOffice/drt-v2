"use strict";

exports.__esModule = true;
const command = exports.command = {
  name: 'emptySelectedCells',
  callback(hot) {
    hot.emptySelectedCells();
    hot._getEditorManager().prepareEditor();
  }
};