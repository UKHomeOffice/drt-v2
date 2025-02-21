"use strict";

exports.__esModule = true;
exports.getValidator = _getItem;
exports.registerValidator = _register;
require("core-js/modules/es.error.cause.js");
var _staticRegister = _interopRequireDefault(require("../utils/staticRegister"));
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
const {
  register,
  getItem,
  hasItem,
  getNames,
  getValues
} = (0, _staticRegister.default)('validators');

/**
 * Retrieve validator function.
 *
 * @param {string} name Validator identification.
 * @returns {Function} Returns validator function.
 */
exports.getRegisteredValidators = getValues;
exports.getRegisteredValidatorNames = getNames;
exports.hasValidator = hasItem;
function _getItem(name) {
  if (typeof name === 'function') {
    return name;
  }
  if (!hasItem(name)) {
    throw Error(`No registered validator found under "${name}" name`);
  }
  return getItem(name);
}

/**
 * Register validator under its alias.
 *
 * @param {string|Function} name Validator's alias or validator function with its descriptor.
 * @param {Function} [validator] Validator function.
 */
function _register(name, validator) {
  if (typeof name !== 'string') {
    validator = name;
    name = validator.VALIDATOR_TYPE;
  }
  register(name, validator);
}