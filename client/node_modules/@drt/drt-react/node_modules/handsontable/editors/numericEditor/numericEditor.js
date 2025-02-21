"use strict";

exports.__esModule = true;
var _textEditor = require("../textEditor");
const EDITOR_TYPE = exports.EDITOR_TYPE = 'numeric';

/**
 * @private
 * @class NumericEditor
 */
class NumericEditor extends _textEditor.TextEditor {
  static get EDITOR_TYPE() {
    return EDITOR_TYPE;
  }
}
exports.NumericEditor = NumericEditor;