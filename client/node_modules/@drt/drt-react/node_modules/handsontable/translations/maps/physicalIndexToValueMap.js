"use strict";

exports.__esModule = true;
var _indexMap = require("./indexMap");
var _physicallyIndexed = require("./utils/physicallyIndexed");
/**
 * Map for storing mappings from an physical index to a value.
 *
 * Does not update stored values on remove/add row or column action.
 *
 * @class PhysicalIndexToValueMap
 */
class PhysicalIndexToValueMap extends _indexMap.IndexMap {
  /**
   * Add values to list and reorganize.
   *
   * @private
   * @param {number} insertionIndex Position inside the list.
   * @param {Array} insertedIndexes List of inserted indexes.
   */
  insert(insertionIndex, insertedIndexes) {
    this.indexedValues = (0, _physicallyIndexed.getListWithInsertedItems)(this.indexedValues, insertionIndex, insertedIndexes, this.initValueOrFn);
    super.insert(insertionIndex, insertedIndexes);
  }

  /**
   * Remove values from the list and reorganize.
   *
   * @private
   * @param {Array} removedIndexes List of removed indexes.
   */
  remove(removedIndexes) {
    this.indexedValues = (0, _physicallyIndexed.getListWithRemovedItems)(this.indexedValues, removedIndexes);
    super.remove(removedIndexes);
  }
}
exports.PhysicalIndexToValueMap = PhysicalIndexToValueMap;