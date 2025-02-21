"use strict";

exports.__esModule = true;
exports.timeValidator = timeValidator;
var _moment = _interopRequireDefault(require("moment"));
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
// Formats which are correctly parsed to time (supported by momentjs)
const STRICT_FORMATS = ['YYYY-MM-DDTHH:mm:ss.SSSZ', 'X',
// Unix timestamp
'x' // Unix ms timestamp
];
const VALIDATOR_TYPE = exports.VALIDATOR_TYPE = 'time';

/**
 * The Time cell validator.
 *
 * @private
 * @param {*} value Value of edited cell.
 * @param {Function} callback Callback called with validation result.
 */
function timeValidator(value, callback) {
  const timeFormat = this.timeFormat || 'h:mm:ss a';
  let valid = true;
  let valueToValidate = value;
  if (valueToValidate === null) {
    valueToValidate = '';
  }
  valueToValidate = /^\d{3,}$/.test(valueToValidate) ? parseInt(valueToValidate, 10) : valueToValidate;
  const twoDigitValue = /^\d{1,2}$/.test(valueToValidate);
  if (twoDigitValue) {
    valueToValidate += ':00';
  }
  const date = (0, _moment.default)(valueToValidate, STRICT_FORMATS, true).isValid() ? (0, _moment.default)(valueToValidate) : (0, _moment.default)(valueToValidate, timeFormat);
  let isValidTime = date.isValid();

  // is it in the specified format
  let isValidFormat = (0, _moment.default)(valueToValidate, timeFormat, true).isValid() && !twoDigitValue;
  if (this.allowEmpty && valueToValidate === '') {
    isValidTime = true;
    isValidFormat = true;
  }
  if (!isValidTime) {
    valid = false;
  }
  if (!isValidTime && isValidFormat) {
    valid = true;
  }
  if (isValidTime && !isValidFormat) {
    if (this.correctFormat === true) {
      // if format correction is enabled
      const correctedValue = date.format(timeFormat);
      this.instance.setDataAtCell(this.visualRow, this.visualCol, correctedValue, 'timeValidator');
      valid = true;
    } else {
      valid = false;
    }
  }
  callback(valid);
}
timeValidator.VALIDATOR_TYPE = VALIDATOR_TYPE;