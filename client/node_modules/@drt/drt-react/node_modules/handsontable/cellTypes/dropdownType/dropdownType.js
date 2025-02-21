"use strict";

exports.__esModule = true;
var _dropdownEditor = require("../../editors/dropdownEditor");
var _dropdownRenderer = require("../../renderers/dropdownRenderer");
var _dropdownValidator = require("../../validators/dropdownValidator");
const CELL_TYPE = exports.CELL_TYPE = 'dropdown';
const DropdownCellType = exports.DropdownCellType = {
  CELL_TYPE,
  editor: _dropdownEditor.DropdownEditor,
  renderer: _dropdownRenderer.dropdownRenderer,
  // displays small gray arrow on right side of the cell
  validator: _dropdownValidator.dropdownValidator,
  filter: false,
  strict: true
};