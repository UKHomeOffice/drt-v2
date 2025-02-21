"use strict";

exports.__esModule = true;
exports.numericValidator = numericValidator;
var _number = require("../../helpers/number");
const VALIDATOR_TYPE = exports.VALIDATOR_TYPE = 'numeric';

/**
 * The Numeric cell validator.
 *
 * @private
 * @param {*} value Value of edited cell.
 * @param {Function} callback Callback called with validation result.
 */
function numericValidator(value, callback) {
  let valueToValidate = value;
  if (valueToValidate === null || valueToValidate === undefined) {
    valueToValidate = '';
  }
  if (this.allowEmpty && valueToValidate === '') {
    callback(true);
  } else if (valueToValidate === '') {
    callback(false);
  } else {
    callback((0, _number.isNumeric)(value));
  }
}
numericValidator.VALIDATOR_TYPE = VALIDATOR_TYPE;