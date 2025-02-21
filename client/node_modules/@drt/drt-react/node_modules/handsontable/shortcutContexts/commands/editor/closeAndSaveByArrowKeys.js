"use strict";

exports.__esModule = true;
var _baseEditor = require("../../../editors/baseEditor");
const command = exports.command = {
  name: 'editorCloseAndSaveByArrowKeys',
  callback(hot, event, keys) {
    const editorManager = hot._getEditorManager();
    const activeEditor = editorManager.getActiveEditor();
    if (activeEditor.isInFullEditMode() && activeEditor.state === _baseEditor.EDITOR_STATE.EDITING) {
      return;
    }
    editorManager.closeEditorAndSaveChanges();
    if (hot.getSelected()) {
      if (keys.includes('arrowdown')) {
        hot.selection.transformStart(1, 0);
      } else if (keys.includes('arrowup')) {
        hot.selection.transformStart(-1, 0);
      } else if (keys.includes('arrowleft')) {
        hot.selection.transformStart(0, -1 * hot.getDirectionFactor());
      } else if (keys.includes('arrowright')) {
        hot.selection.transformStart(0, hot.getDirectionFactor());
      }
    }
    event.preventDefault();
  }
};