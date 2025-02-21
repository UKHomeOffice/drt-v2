"use strict";

exports.__esModule = true;
require("core-js/modules/esnext.iterator.map.js");
var _array = require("../../../helpers/array");
var _mixed = require("../../../helpers/mixed");
var _base = _interopRequireDefault(require("./_base"));
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
const CHAR_CARRIAGE_RETURN = String.fromCharCode(13);
const CHAR_DOUBLE_QUOTES = String.fromCharCode(34);
const CHAR_LINE_FEED = String.fromCharCode(10);

/**
 * @private
 */
class Csv extends _base.default {
  /**
   * Default options for exporting CSV format.
   *
   * @returns {object}
   */
  static get DEFAULT_OPTIONS() {
    return {
      mimeType: 'text/csv',
      fileExtension: 'csv',
      bom: true,
      columnDelimiter: ',',
      rowDelimiter: '\r\n'
    };
  }

  /**
   * Create string body in desired format.
   *
   * @returns {string}
   */
  export() {
    const options = this.options;
    const data = this.dataProvider.getData();
    let columnHeaders = this.dataProvider.getColumnHeaders();
    const hasColumnHeaders = columnHeaders.length > 0;
    const rowHeaders = this.dataProvider.getRowHeaders();
    const hasRowHeaders = rowHeaders.length > 0;
    let result = options.bom ? String.fromCharCode(0xFEFF) : '';
    if (hasColumnHeaders) {
      columnHeaders = (0, _array.arrayMap)(columnHeaders, value => this._escapeCell(value, true));
      if (hasRowHeaders) {
        result += options.columnDelimiter;
      }
      result += columnHeaders.join(options.columnDelimiter);
      result += options.rowDelimiter;
    }
    (0, _array.arrayEach)(data, (value, index) => {
      if (index > 0) {
        result += options.rowDelimiter;
      }
      if (hasRowHeaders) {
        result += this._escapeCell(rowHeaders[index]) + options.columnDelimiter;
      }
      result += value.map(cellValue => this._escapeCell(cellValue)).join(options.columnDelimiter);
    });
    return result;
  }

  /**
   * Escape cell value.
   *
   * @param {*} value Cell value.
   * @param {boolean} [force=false] Indicates if cell value will be escaped forcefully.
   * @returns {string}
   */
  _escapeCell(value) {
    let force = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : false;
    let escapedValue = (0, _mixed.stringify)(value);
    if (escapedValue !== '' && (force || escapedValue.indexOf(CHAR_CARRIAGE_RETURN) >= 0 || escapedValue.indexOf(CHAR_DOUBLE_QUOTES) >= 0 || escapedValue.indexOf(CHAR_LINE_FEED) >= 0 || escapedValue.indexOf(this.options.columnDelimiter) >= 0)) {
      escapedValue = escapedValue.replace(new RegExp('"', 'g'), '""');
      escapedValue = `"${escapedValue}"`;
    }
    return escapedValue;
  }
}
var _default = exports.default = Csv;