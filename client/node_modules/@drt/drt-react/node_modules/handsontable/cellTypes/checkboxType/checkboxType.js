"use strict";

exports.__esModule = true;
var _checkboxEditor = require("../../editors/checkboxEditor");
var _checkboxRenderer = require("../../renderers/checkboxRenderer");
const CELL_TYPE = exports.CELL_TYPE = 'checkbox';
const CheckboxCellType = exports.CheckboxCellType = {
  CELL_TYPE,
  editor: _checkboxEditor.CheckboxEditor,
  renderer: _checkboxRenderer.checkboxRenderer
};