"use strict";

exports.__esModule = true;
exports.shortcutsEditorContext = shortcutsEditorContext;
var _constants = require("./constants");
var _commands = require("./commands");
/**
 * The context that defines a base shortcut list available for cells editors.
 *
 * @param {Handsontable} hot The Handsontable instance.
 */
function shortcutsEditorContext(hot) {
  const context = hot.getShortcutManager().addContext('editor');
  const commandsPool = (0, _commands.createKeyboardShortcutCommandsPool)(hot);
  const config = {
    group: _constants.EDITOR_EDIT_GROUP
  };
  context.addShortcuts([{
    keys: [['Enter'], ['Enter', 'Shift']],
    callback: (event, keys) => commandsPool.editorCloseAndSaveByEnter(event, keys)
  }, {
    keys: [['Enter', 'Control/Meta'], ['Enter', 'Control/Meta', 'Shift']],
    captureCtrl: true,
    callback: (event, keys) => commandsPool.editorCloseAndSaveByEnter(event, keys)
  }, {
    keys: [['Tab'], ['Tab', 'Shift'], ['PageDown'], ['PageUp']],
    forwardToContext: hot.getShortcutManager().getContext('grid'),
    callback: (event, keys) => commandsPool.editorCloseAndSave(event, keys)
  }, {
    keys: [['ArrowDown'], ['ArrowUp'], ['ArrowLeft'], ['ArrowRight']],
    preventDefault: false,
    callback: (event, keys) => commandsPool.editorCloseAndSaveByArrowKeys(event, keys)
  }, {
    keys: [['Escape'], ['Escape', 'Control/Meta']],
    callback: () => commandsPool.editorCloseWithoutSaving()
  }], config);
}