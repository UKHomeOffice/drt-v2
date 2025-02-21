"use strict";

exports.__esModule = true;
const command = exports.command = {
  name: 'editorFastOpen',
  callback(hot, event) {
    const {
      highlight
    } = hot.getSelectedRangeLast();
    if (highlight.isHeader()) {
      return;
    }
    hot._getEditorManager().openEditor(null, event, true);
  }
};