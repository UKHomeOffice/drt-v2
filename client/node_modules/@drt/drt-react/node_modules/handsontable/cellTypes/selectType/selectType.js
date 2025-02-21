"use strict";

exports.__esModule = true;
var _selectEditor = require("../../editors/selectEditor");
var _selectRenderer = require("../../renderers/selectRenderer");
const CELL_TYPE = exports.CELL_TYPE = 'select';
const SelectCellType = exports.SelectCellType = {
  CELL_TYPE,
  editor: _selectEditor.SelectEditor,
  renderer: _selectRenderer.selectRenderer
};