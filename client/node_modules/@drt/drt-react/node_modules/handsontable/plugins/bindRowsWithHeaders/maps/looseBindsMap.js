"use strict";

exports.__esModule = true;
var _translations = require("../../../translations");
const {
  getListWithInsertedItems,
  getListWithRemovedItems
} = (0, _translations.alterUtilsFactory)('physicallyIndexed');

/**
 * Map from physical index to another index.
 */
class LooseBindsMap extends _translations.IndexMap {
  constructor() {
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
    const listAfterUpdate = (0, _translations.getIncreasedIndexes)(this.indexedValues, insertedIndexes);
    this.indexedValues = getListWithInsertedItems(listAfterUpdate, insertionIndex, insertedIndexes, this.initValueOrFn);
    super.insert(insertionIndex, insertedIndexes);
  }

  /**
   * Remove values from the list and reorganize.
   *
   * @private
   * @param {Array} removedIndexes List of removed indexes.
   */
  remove(removedIndexes) {
    const listAfterUpdate = getListWithRemovedItems(this.indexedValues, removedIndexes);
    this.indexedValues = (0, _translations.getDecreasedIndexes)(listAfterUpdate, removedIndexes);
    super.remove(removedIndexes);
  }
}
var _default = exports.default = LooseBindsMap;