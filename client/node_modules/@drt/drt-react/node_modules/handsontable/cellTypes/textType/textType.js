"use strict";

exports.__esModule = true;
var _textEditor = require("../../editors/textEditor");
var _textRenderer = require("../../renderers/textRenderer");
const CELL_TYPE = exports.CELL_TYPE = 'text';
const TextCellType = exports.TextCellType = {
  CELL_TYPE,
  editor: _textEditor.TextEditor,
  renderer: _textRenderer.textRenderer
};