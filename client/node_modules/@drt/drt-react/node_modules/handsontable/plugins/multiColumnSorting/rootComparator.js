"use strict";

exports.__esModule = true;
exports.rootComparator = rootComparator;
var _sortService = require("../columnSorting/sortService");
/**
 * Sort comparator handled by conventional sort algorithm.
 *
 * @param {Array} sortingOrders Sort orders (`asc` for ascending, `desc` for descending).
 * @param {Array} columnMetas Column meta objects.
 * @returns {Function}
 */
function rootComparator(sortingOrders, columnMetas) {
  return function (rowIndexWithValues, nextRowIndexWithValues) {
    // We sort array of arrays. Single array is in form [rowIndex, ...values].
    // We compare just values, stored at second index of array.
    const [, ...values] = rowIndexWithValues;
    const [, ...nextValues] = nextRowIndexWithValues;
    return function getCompareResult(column) {
      const sortingOrder = sortingOrders[column];
      const columnMeta = columnMetas[column];
      const value = values[column];
      const nextValue = nextValues[column];
      const pluginSettings = columnMeta.multiColumnSorting;
      const compareFunctionFactory = pluginSettings.compareFunctionFactory ? pluginSettings.compareFunctionFactory : (0, _sortService.getCompareFunctionFactory)(columnMeta.type);
      const compareResult = compareFunctionFactory(sortingOrder, columnMeta, pluginSettings)(value, nextValue);
      if (compareResult === _sortService.DO_NOT_SWAP) {
        const nextSortedColumn = column + 1;
        if (typeof columnMetas[nextSortedColumn] !== 'undefined') {
          return getCompareResult(nextSortedColumn);
        }
      }
      return compareResult;
    }(0);
  };
}