"use strict";

exports.__esModule = true;
exports.dropdownValidator = dropdownValidator;
var _autocompleteValidator = require("../autocompleteValidator/autocompleteValidator");
const VALIDATOR_TYPE = exports.VALIDATOR_TYPE = 'dropdown';

/**
 * The Dropdown cell validator.
 *
 * @private
 * @param {*} value Value of edited cell.
 * @param {Function} callback Callback called with validation result.
 */
function dropdownValidator(value, callback) {
  _autocompleteValidator.autocompleteValidator.apply(this, [value, callback]);
}
dropdownValidator.VALIDATOR_TYPE = VALIDATOR_TYPE;