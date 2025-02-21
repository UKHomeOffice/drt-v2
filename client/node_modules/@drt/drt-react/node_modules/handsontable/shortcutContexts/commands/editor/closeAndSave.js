"use strict";

exports.__esModule = true;
const command = exports.command = {
  name: 'editorCloseAndSave',
  callback(hot) {
    const editorManager = hot._getEditorManager();
    editorManager.closeEditorAndSaveChanges();
  }
};