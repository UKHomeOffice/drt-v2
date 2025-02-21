"use strict";

exports.__esModule = true;
exports.compareFunctionFactory = compareFunctionFactory;
var _mixed = require("../../../helpers/mixed");
var _sortService = require("../sortService");
/**
 * Numeric sorting compare function factory. Method get as parameters `sortOrder` and `columnMeta` and return compare function.
 *
 * @param {string} sortOrder Sort order (`asc` for ascending, `desc` for descending).
 * @param {object} columnMeta Column meta object.
 * @param {object} columnPluginSettings Plugin settings for the column.
 * @returns {Function} The compare function.
 */
function compareFunctionFactory(sortOrder, columnMeta, columnPluginSettings) {
  return function (value, nextValue) {
    const parsedFirstValue = parseFloat(value);
    const parsedSecondValue = parseFloat(nextValue);
    const {
      sortEmptyCells
    } = columnPluginSettings;

    // Watch out when changing this part of code! Check below returns 0 (as expected) when comparing empty string, null, undefined
    if (parsedFirstValue === parsedSecondValue || isNaN(parsedFirstValue) && isNaN(parsedSecondValue)) {
      return _sortService.DO_NOT_SWAP;
    }
    if (sortEmptyCells) {
      if ((0, _mixed.isEmpty)(value)) {
        return sortOrder === 'asc' ? _sortService.FIRST_BEFORE_SECOND : _sortService.FIRST_AFTER_SECOND;
      }
      if ((0, _mixed.isEmpty)(nextValue)) {
        return sortOrder === 'asc' ? _sortService.FIRST_AFTER_SECOND : _sortService.FIRST_BEFORE_SECOND;
      }
    }
    if (isNaN(parsedFirstValue)) {
      return _sortService.FIRST_AFTER_SECOND;
    }
    if (isNaN(parsedSecondValue)) {
      return _sortService.FIRST_BEFORE_SECOND;
    }
    if (parsedFirstValue < parsedSecondValue) {
      return sortOrder === 'asc' ? _sortService.FIRST_BEFORE_SECOND : _sortService.FIRST_AFTER_SECOND;
    } else if (parsedFirstValue > parsedSecondValue) {
      return sortOrder === 'asc' ? _sortService.FIRST_AFTER_SECOND : _sortService.FIRST_BEFORE_SECOND;
    }
    return _sortService.DO_NOT_SWAP;
  };
}
const COLUMN_DATA_TYPE = exports.COLUMN_DATA_TYPE = 'numeric';