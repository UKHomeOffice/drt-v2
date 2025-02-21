"use strict";

exports.__esModule = true;
exports.registerAllCellTypes = registerAllCellTypes;
var _autocompleteType = require("./autocompleteType");
exports.AutocompleteCellType = _autocompleteType.AutocompleteCellType;
exports.AUTOCOMPLETE_TYPE = _autocompleteType.CELL_TYPE;
var _checkboxType = require("./checkboxType");
exports.CheckboxCellType = _checkboxType.CheckboxCellType;
exports.CHECKBOX_TYPE = _checkboxType.CELL_TYPE;
var _dateType = require("./dateType");
exports.DateCellType = _dateType.DateCellType;
exports.DATE_TYPE = _dateType.CELL_TYPE;
var _dropdownType = require("./dropdownType");
exports.DropdownCellType = _dropdownType.DropdownCellType;
exports.DROPDOWN_TYPE = _dropdownType.CELL_TYPE;
var _handsontableType = require("./handsontableType");
exports.HandsontableCellType = _handsontableType.HandsontableCellType;
exports.HANDSONTABLE_TYPE = _handsontableType.CELL_TYPE;
var _numericType = require("./numericType");
exports.NumericCellType = _numericType.NumericCellType;
exports.NUMERIC_TYPE = _numericType.CELL_TYPE;
var _passwordType = require("./passwordType");
exports.PasswordCellType = _passwordType.PasswordCellType;
exports.PASSWORD_TYPE = _passwordType.CELL_TYPE;
var _selectType = require("./selectType");
exports.SelectCellType = _selectType.SelectCellType;
exports.SELECT_TYPE = _selectType.CELL_TYPE;
var _textType = require("./textType");
exports.TextCellType = _textType.TextCellType;
exports.TEXT_TYPE = _textType.CELL_TYPE;
var _timeType = require("./timeType");
exports.TimeCellType = _timeType.TimeCellType;
exports.TIME_TYPE = _timeType.CELL_TYPE;
var _registry = require("./registry");
exports.registerCellType = _registry.registerCellType;
exports.getCellType = _registry.getCellType;
exports.getRegisteredCellTypeNames = _registry.getRegisteredCellTypeNames;
exports.getRegisteredCellTypes = _registry.getRegisteredCellTypes;
exports.hasCellType = _registry.hasCellType;
/**
 * Registers all available cell types.
 */
function registerAllCellTypes() {
  (0, _registry.registerCellType)(_autocompleteType.AutocompleteCellType);
  (0, _registry.registerCellType)(_checkboxType.CheckboxCellType);
  (0, _registry.registerCellType)(_dateType.DateCellType);
  (0, _registry.registerCellType)(_dropdownType.DropdownCellType);
  (0, _registry.registerCellType)(_handsontableType.HandsontableCellType);
  (0, _registry.registerCellType)(_numericType.NumericCellType);
  (0, _registry.registerCellType)(_passwordType.PasswordCellType);
  (0, _registry.registerCellType)(_selectType.SelectCellType);
  (0, _registry.registerCellType)(_textType.TextCellType);
  (0, _registry.registerCellType)(_timeType.TimeCellType);
}