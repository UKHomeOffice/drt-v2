"use strict";

exports.__esModule = true;
var _dateEditor = require("../../editors/dateEditor");
var _dateRenderer = require("../../renderers/dateRenderer");
var _dateValidator = require("../../validators/dateValidator");
const CELL_TYPE = exports.CELL_TYPE = 'date';
const DateCellType = exports.DateCellType = {
  CELL_TYPE,
  editor: _dateEditor.DateEditor,
  // displays small gray arrow on right side of the cell
  renderer: _dateRenderer.dateRenderer,
  validator: _dateValidator.dateValidator
};