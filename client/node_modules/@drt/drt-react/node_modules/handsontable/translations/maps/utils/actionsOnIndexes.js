"use strict";

exports.__esModule = true;
exports.getDecreasedIndexes = getDecreasedIndexes;
exports.getIncreasedIndexes = getIncreasedIndexes;
require("core-js/modules/esnext.iterator.constructor.js");
require("core-js/modules/esnext.iterator.filter.js");
var _array = require("../../../helpers/array");
/**
 * Transform mappings after removal.
 *
 * @private
 * @param {Array} indexedValues List of values for particular indexes.
 * @param {Array} removedIndexes List of removed indexes.
 * @returns {Array} List with decreased indexes.
 */
function getDecreasedIndexes(indexedValues, removedIndexes) {
  return (0, _array.arrayMap)(indexedValues, index => index - removedIndexes.filter(removedIndex => removedIndex < index).length);
}

/**
 * Transform mappings after insertion.
 *
 * @private
 * @param {Array} indexedValues List of values for particular indexes.
 * @param {Array} insertedIndexes List of inserted indexes.
 * @returns {Array} List with increased indexes.
 */
function getIncreasedIndexes(indexedValues, insertedIndexes) {
  const firstInsertedIndex = insertedIndexes[0];
  const amountOfIndexes = insertedIndexes.length;
  return (0, _array.arrayMap)(indexedValues, index => {
    if (index >= firstInsertedIndex) {
      return index + amountOfIndexes;
    }
    return index;
  });
}