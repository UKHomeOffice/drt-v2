"use strict";

exports.__esModule = true;
var _autocompleteEditor = require("../../editors/autocompleteEditor");
var _autocompleteRenderer = require("../../renderers/autocompleteRenderer");
var _autocompleteValidator = require("../../validators/autocompleteValidator");
const CELL_TYPE = exports.CELL_TYPE = 'autocomplete';
const AutocompleteCellType = exports.AutocompleteCellType = {
  CELL_TYPE,
  editor: _autocompleteEditor.AutocompleteEditor,
  renderer: _autocompleteRenderer.autocompleteRenderer,
  validator: _autocompleteValidator.autocompleteValidator
};