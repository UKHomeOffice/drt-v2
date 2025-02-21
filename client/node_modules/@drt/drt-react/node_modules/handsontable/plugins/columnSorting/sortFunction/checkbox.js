"use strict";

exports.__esModule = true;
exports.compareFunctionFactory = compareFunctionFactory;
var _sortService = require("../sortService");
var _default = require("../sortFunction/default");
var _mixed = require("../../../helpers/mixed");
/**
 * Checkbox sorting compare function factory. Method get as parameters `sortOrder` and `columnMeta` and return compare function.
 *
 * @param {string} sortOrder Sort order (`asc` for ascending, `desc` for descending).
 * @param {object} columnMeta Column meta object.
 * @param {object} columnPluginSettings Plugin settings for the column.
 * @returns {Function} The compare function.
 */
function compareFunctionFactory(sortOrder, columnMeta, columnPluginSettings) {
  const checkedTemplate = columnMeta.checkedTemplate;
  const uncheckedTemplate = columnMeta.uncheckedTemplate;
  const {
    sortEmptyCells
  } = columnPluginSettings;
  return function (value, nextValue) {
    const isEmptyValue = (0, _mixed.isEmpty)(value);
    const isEmptyNextValue = (0, _mixed.isEmpty)(nextValue);
    const unifiedValue = isEmptyValue ? uncheckedTemplate : value;
    const unifiedNextValue = isEmptyNextValue ? uncheckedTemplate : nextValue;
    const isValueFromTemplate = unifiedValue === uncheckedTemplate || unifiedValue === checkedTemplate;
    const isNextValueFromTemplate = unifiedNextValue === uncheckedTemplate || unifiedNextValue === checkedTemplate;

    // As an empty cell we recognize cells with undefined, null and '' values.
    if (sortEmptyCells === false) {
      if (isEmptyValue && isEmptyNextValue === false) {
        return _sortService.FIRST_AFTER_SECOND;
      }
      if (isEmptyValue === false && isEmptyNextValue) {
        return _sortService.FIRST_BEFORE_SECOND;
      }
    }

    // 1st value === #BAD_VALUE#
    if (isValueFromTemplate === false && isNextValueFromTemplate) {
      return sortOrder === 'asc' ? _sortService.FIRST_BEFORE_SECOND : _sortService.FIRST_AFTER_SECOND;
    }

    // 2nd value === #BAD_VALUE#
    if (isValueFromTemplate && isNextValueFromTemplate === false) {
      return sortOrder === 'asc' ? _sortService.FIRST_AFTER_SECOND : _sortService.FIRST_BEFORE_SECOND;
    }

    // 1st value === #BAD_VALUE# && 2nd value === #BAD_VALUE#
    if (isValueFromTemplate === false && isNextValueFromTemplate === false) {
      // Sorting by values (not just by visual representation).
      return (0, _default.compareFunctionFactory)(sortOrder, columnMeta, columnPluginSettings)(value, nextValue);
    }
    if (unifiedValue === uncheckedTemplate && unifiedNextValue === checkedTemplate) {
      return sortOrder === 'asc' ? _sortService.FIRST_BEFORE_SECOND : _sortService.FIRST_AFTER_SECOND;
    }
    if (unifiedValue === checkedTemplate && unifiedNextValue === uncheckedTemplate) {
      return sortOrder === 'asc' ? _sortService.FIRST_AFTER_SECOND : _sortService.FIRST_BEFORE_SECOND;
    }
    return _sortService.DO_NOT_SWAP;
  };
}
const COLUMN_DATA_TYPE = exports.COLUMN_DATA_TYPE = 'checkbox';