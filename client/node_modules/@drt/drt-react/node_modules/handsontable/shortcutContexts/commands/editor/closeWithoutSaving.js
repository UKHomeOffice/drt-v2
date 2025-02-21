"use strict";

exports.__esModule = true;
const command = exports.command = {
  name: 'editorCloseWithoutSaving',
  callback(hot) {
    const editorManager = hot._getEditorManager();
    editorManager.closeEditorAndRestoreOriginalValue(hot.getShortcutManager().isCtrlPressed());
    editorManager.activeEditor.focus();
  }
};