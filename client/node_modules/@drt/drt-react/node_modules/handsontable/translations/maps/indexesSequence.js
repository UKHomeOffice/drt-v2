"use strict";

exports.__esModule = true;
var _indexMap = require("./indexMap");
var _indexesSequence = require("./utils/indexesSequence");
var _utils = require("./utils");
/**
 * Map for storing mappings from an index to a physical index.
 *
 * It also updates the physical indexes (remaining in the map) on remove/add row or column action.
 *
 * @class IndexesSequence
 */
class IndexesSequence extends _indexMap.IndexMap {
  constructor() {
    // Not handling custom init function or init value.
    super(index => index);
  }

  /**
   * Add values to list and reorganize.
   *
   * @private
   * @param {number} insertionIndex Position inside the list.
   * @param {Array} insertedIndexes List of inserted indexes.
   */
  insert(insertionIndex, insertedIndexes) {
    const listAfterUpdate = (0, _utils.getIncreasedIndexes)(this.indexedValues, insertedIndexes);
    this.indexedValues = (0, _indexesSequence.getListWithInsertedItems)(listAfterUpdate, insertionIndex, insertedIndexes);
    super.insert(insertionIndex, insertedIndexes);
  }

  /**
   * Remove values from the list and reorganize.
   *
   * @private
   * @param {Array} removedIndexes List of removed indexes.
   */
  remove(removedIndexes) {
    const listAfterUpdate = (0, _indexesSequence.getListWithRemovedItems)(this.indexedValues, removedIndexes);
    this.indexedValues = (0, _utils.getDecreasedIndexes)(listAfterUpdate, removedIndexes);
    super.remove(removedIndexes);
  }
}
exports.IndexesSequence = IndexesSequence;