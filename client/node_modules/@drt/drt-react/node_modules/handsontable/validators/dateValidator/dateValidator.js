"use strict";

exports.__esModule = true;
exports.correctFormat = correctFormat;
exports.dateValidator = dateValidator;
var _moment = _interopRequireDefault(require("moment"));
var _registry = require("../../editors/registry");
var _dateEditor = require("../../editors/dateEditor");
var _date = require("../../helpers/date");
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
const VALIDATOR_TYPE = exports.VALIDATOR_TYPE = 'date';

/**
 * The Date cell validator.
 *
 * @private
 * @param {*} value Value of edited cell.
 * @param {Function} callback Callback called with validation result.
 */
function dateValidator(value, callback) {
  const dateEditor = (0, _registry.getEditorInstance)(_dateEditor.EDITOR_TYPE, this.instance);
  let valueToValidate = value;
  let valid = true;
  if (valueToValidate === null || valueToValidate === undefined) {
    valueToValidate = '';
  }
  let isValidFormat = (0, _moment.default)(valueToValidate, this.dateFormat || dateEditor.defaultDateFormat, true).isValid();
  let isValidDate = (0, _moment.default)(new Date(valueToValidate)).isValid() || isValidFormat;
  if (this.allowEmpty && valueToValidate === '') {
    isValidDate = true;
    isValidFormat = true;
  }
  if (!isValidDate) {
    valid = false;
  }
  if (!isValidDate && isValidFormat) {
    valid = true;
  }
  if (isValidDate && !isValidFormat) {
    if (this.correctFormat === true) {
      // if format correction is enabled
      const correctedValue = correctFormat(valueToValidate, this.dateFormat);
      this.instance.setDataAtCell(this.visualRow, this.visualCol, correctedValue, 'dateValidator');
      valid = true;
    } else {
      valid = false;
    }
  }
  callback(valid);
}
dateValidator.VALIDATOR_TYPE = VALIDATOR_TYPE;

/**
 * Format the given string using moment.js' format feature.
 *
 * @param {string} value The value to format.
 * @param {string} dateFormat The date pattern to format to.
 * @returns {string}
 */
function correctFormat(value, dateFormat) {
  const dateFromDate = (0, _moment.default)((0, _date.getNormalizedDate)(value));
  const dateFromMoment = (0, _moment.default)(value, dateFormat);
  const isAlphanumeric = value.search(/[A-Za-z]/g) > -1;
  let date;
  if (dateFromDate.isValid() && dateFromDate.format('x') === dateFromMoment.format('x') || !dateFromMoment.isValid() || isAlphanumeric) {
    date = dateFromDate;
  } else {
    date = dateFromMoment;
  }
  return date.format(dateFormat);
}