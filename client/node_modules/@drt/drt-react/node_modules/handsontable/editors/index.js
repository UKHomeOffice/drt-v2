"use strict";

exports.__esModule = true;
exports.registerAllEditors = registerAllEditors;
var _autocompleteEditor = require("./autocompleteEditor");
exports.AutocompleteEditor = _autocompleteEditor.AutocompleteEditor;
exports.AUTOCOMPLETE_EDITOR = _autocompleteEditor.EDITOR_TYPE;
var _baseEditor = require("./baseEditor");
exports.BaseEditor = _baseEditor.BaseEditor;
exports.BASE_EDITOR = _baseEditor.EDITOR_TYPE;
var _checkboxEditor = require("./checkboxEditor");
exports.CheckboxEditor = _checkboxEditor.CheckboxEditor;
exports.CHECKBOX_EDITOR = _checkboxEditor.EDITOR_TYPE;
var _dateEditor = require("./dateEditor");
exports.DateEditor = _dateEditor.DateEditor;
exports.DATE_EDITOR = _dateEditor.EDITOR_TYPE;
var _dropdownEditor = require("./dropdownEditor");
exports.DropdownEditor = _dropdownEditor.DropdownEditor;
exports.DROPDOWN_EDITOR = _dropdownEditor.EDITOR_TYPE;
var _handsontableEditor = require("./handsontableEditor");
exports.HandsontableEditor = _handsontableEditor.HandsontableEditor;
exports.HANDSONTABLE_EDITOR = _handsontableEditor.EDITOR_TYPE;
var _numericEditor = require("./numericEditor");
exports.NumericEditor = _numericEditor.NumericEditor;
exports.NUMERIC_EDITOR = _numericEditor.EDITOR_TYPE;
var _passwordEditor = require("./passwordEditor");
exports.PasswordEditor = _passwordEditor.PasswordEditor;
exports.PASSWORD_EDITOR = _passwordEditor.EDITOR_TYPE;
var _selectEditor = require("./selectEditor");
exports.SelectEditor = _selectEditor.SelectEditor;
exports.SELECT_EDITOR = _selectEditor.EDITOR_TYPE;
var _textEditor = require("./textEditor");
exports.TextEditor = _textEditor.TextEditor;
exports.TEXT_EDITOR = _textEditor.EDITOR_TYPE;
var _timeEditor = require("./timeEditor");
exports.TimeEditor = _timeEditor.TimeEditor;
exports.TIME_EDITOR = _timeEditor.EDITOR_TYPE;
var _registry = require("./registry");
exports.registerEditor = _registry.registerEditor;
exports.RegisteredEditor = _registry.RegisteredEditor;
exports._getEditorInstance = _registry._getEditorInstance;
exports.getEditor = _registry.getEditor;
exports.getEditorInstance = _registry.getEditorInstance;
exports.getRegisteredEditorNames = _registry.getRegisteredEditorNames;
exports.getRegisteredEditors = _registry.getRegisteredEditors;
exports.hasEditor = _registry.hasEditor;
/**
 * Registers all available editors.
 */
function registerAllEditors() {
  (0, _registry.registerEditor)(_baseEditor.BaseEditor);
  (0, _registry.registerEditor)(_autocompleteEditor.AutocompleteEditor);
  (0, _registry.registerEditor)(_checkboxEditor.CheckboxEditor);
  (0, _registry.registerEditor)(_dateEditor.DateEditor);
  (0, _registry.registerEditor)(_dropdownEditor.DropdownEditor);
  (0, _registry.registerEditor)(_handsontableEditor.HandsontableEditor);
  (0, _registry.registerEditor)(_numericEditor.NumericEditor);
  (0, _registry.registerEditor)(_passwordEditor.PasswordEditor);
  (0, _registry.registerEditor)(_selectEditor.SelectEditor);
  (0, _registry.registerEditor)(_textEditor.TextEditor);
  (0, _registry.registerEditor)(_timeEditor.TimeEditor);
}