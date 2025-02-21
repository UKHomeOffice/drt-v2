"use strict";

exports.__esModule = true;
exports.compareFunctionFactory = compareFunctionFactory;
var _utils = require("../utils");
/**
 * Date sorting compare function factory. Method get as parameters `sortOrder` and `columnMeta` and return compare function.
 *
 * @param {string} sortOrder Sort order (`asc` for ascending, `desc` for descending).
 * @param {object} columnMeta Column meta object.
 * @param {object} columnPluginSettings Plugin settings for the column.
 * @returns {Function} The compare function.
 */
function compareFunctionFactory(sortOrder, columnMeta, columnPluginSettings) {
  return (0, _utils.createDateTimeCompareFunction)(sortOrder, columnMeta.dateFormat, columnPluginSettings);
}
const COLUMN_DATA_TYPE = exports.COLUMN_DATA_TYPE = 'date';