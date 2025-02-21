"use strict";

exports.__esModule = true;
exports.sort = sort;
var _registry = require("./registry");
const DO_NOT_SWAP = exports.DO_NOT_SWAP = 0;
const FIRST_BEFORE_SECOND = exports.FIRST_BEFORE_SECOND = -1;
const FIRST_AFTER_SECOND = exports.FIRST_AFTER_SECOND = 1;

/**
 * @param {Array} indexesWithData The data to sort.
 * @param {string} rootComparatorId The comparator logic to use.
 * @param {Array} argsForRootComparator Additional arguments for comparator function.
 */
function sort(indexesWithData, rootComparatorId) {
  const rootComparator = (0, _registry.getRootComparator)(rootComparatorId);
  for (var _len = arguments.length, argsForRootComparator = new Array(_len > 2 ? _len - 2 : 0), _key = 2; _key < _len; _key++) {
    argsForRootComparator[_key - 2] = arguments[_key];
  }
  indexesWithData.sort(rootComparator(...argsForRootComparator));
}