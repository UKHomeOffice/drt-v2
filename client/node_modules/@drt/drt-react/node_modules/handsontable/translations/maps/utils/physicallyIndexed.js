"use strict";

exports.__esModule = true;
exports.getListWithInsertedItems = getListWithInsertedItems;
exports.getListWithRemovedItems = getListWithRemovedItems;
require("core-js/modules/esnext.iterator.map.js");
var _function = require("../../../helpers/function");
var _array = require("../../../helpers/array");
/**
 * Insert new items to the list.
 *
 * @private
 * @param {Array} indexedValues List of values for particular indexes.
 * @param {number} insertionIndex Position inside the actual list.
 * @param {Array} insertedIndexes List of inserted indexes.
 * @param {*} insertedValuesMapping Mapping which may provide value or function returning value for the specific parameters.
 * @returns {Array} List with new mappings.
 */
function getListWithInsertedItems(indexedValues, insertionIndex, insertedIndexes, insertedValuesMapping) {
  const firstInsertedIndex = insertedIndexes.length ? insertedIndexes[0] : undefined;
  return [...indexedValues.slice(0, firstInsertedIndex), ...insertedIndexes.map((insertedIndex, ordinalNumber) => {
    if ((0, _function.isFunction)(insertedValuesMapping)) {
      return insertedValuesMapping(insertedIndex, ordinalNumber);
    }
    return insertedValuesMapping;
  }), ...(firstInsertedIndex === undefined ? [] : indexedValues.slice(firstInsertedIndex))];
}

/**
 * Filter items from the list.
 *
 * @private
 * @param {Array} indexedValues List of values for particular indexes.
 * @param {Array} removedIndexes List of removed indexes.
 * @returns {Array} Reduced list of mappings.
 */
function getListWithRemovedItems(indexedValues, removedIndexes) {
  return (0, _array.arrayFilter)(indexedValues, (_, index) => removedIndexes.includes(index) === false);
}