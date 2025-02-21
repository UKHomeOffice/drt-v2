"use strict";

exports.__esModule = true;
var _handsontableEditor = require("../../editors/handsontableEditor");
var _handsontableRenderer = require("../../renderers/handsontableRenderer");
const CELL_TYPE = exports.CELL_TYPE = 'handsontable';
const HandsontableCellType = exports.HandsontableCellType = {
  CELL_TYPE,
  editor: _handsontableEditor.HandsontableEditor,
  // displays small gray arrow on right side of the cell
  renderer: _handsontableRenderer.handsontableRenderer
};