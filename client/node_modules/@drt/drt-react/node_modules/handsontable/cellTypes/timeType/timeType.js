"use strict";

exports.__esModule = true;
var _timeEditor = require("../../editors/timeEditor");
var _timeRenderer = require("../../renderers/timeRenderer");
var _timeValidator = require("../../validators/timeValidator");
const CELL_TYPE = exports.CELL_TYPE = 'time';
const TimeCellType = exports.TimeCellType = {
  CELL_TYPE,
  editor: _timeEditor.TimeEditor,
  renderer: _timeRenderer.timeRenderer,
  validator: _timeValidator.timeValidator
};