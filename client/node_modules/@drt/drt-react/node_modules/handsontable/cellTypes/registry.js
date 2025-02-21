"use strict";

exports.__esModule = true;
exports.getCellType = _getItem;
exports.registerCellType = _register;
require("core-js/modules/es.error.cause.js");
var _staticRegister = _interopRequireDefault(require("../utils/staticRegister"));
var _registry = require("../editors/registry");
var _registry2 = require("../renderers/registry");
var _registry3 = require("../validators/registry");
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
const {
  register,
  getItem,
  hasItem,
  getNames,
  getValues
} = (0, _staticRegister.default)('cellTypes');

/**
 * Retrieve cell type object.
 *
 * @param {string} name Cell type identification.
 * @returns {object} Returns cell type object.
 */
exports.getRegisteredCellTypes = getValues;
exports.getRegisteredCellTypeNames = getNames;
exports.hasCellType = hasItem;
function _getItem(name) {
  if (!hasItem(name)) {
    throw Error(`You declared cell type "${name}" as a string that is not mapped to a known object.
                 Cell type must be an object or a string mapped to an object registered by
                 "Handsontable.cellTypes.registerCellType" method`);
  }
  return getItem(name);
}

/**
 * Register cell type under specified name.
 *
 * @param {string} name Cell type identification.
 * @param {object} type An object with contains keys (eq: `editor`, `renderer`, `validator`) which describes specified behaviour of the cell.
 */
function _register(name, type) {
  if (typeof name !== 'string') {
    type = name;
    name = type.CELL_TYPE;
  }
  const {
    editor,
    renderer,
    validator
  } = type;
  if (editor) {
    (0, _registry.registerEditor)(name, editor);
  }
  if (renderer) {
    (0, _registry2.registerRenderer)(name, renderer);
  }
  if (validator) {
    (0, _registry3.registerValidator)(name, validator);
  }
  register(name, type);
}