import "core-js/modules/es.error.cause.js";
import "core-js/modules/esnext.iterator.map.js";
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
import { IndexMap } from "./indexMap.mjs";
import { getListWithRemovedItems, getListWithInsertedItems } from "./utils/physicallyIndexed.mjs";
import { getListWithRemovedItems as getListWithoutIndexes } from "./utils/indexesSequence.mjs";
import { getDecreasedIndexes, getIncreasedIndexes } from "./utils/actionsOnIndexes.mjs";
import { isFunction } from "../../helpers/function.mjs";
/**
 * Map for storing mappings from an physical index to a value. Those entries are linked and stored in a certain order.
 *
 * It does not update stored values on remove/add row or column action. Otherwise, order of entries is updated after
 * such changes.
 *
 * @class LinkedPhysicalIndexToValueMap
 */
export class LinkedPhysicalIndexToValueMap extends IndexMap {
  constructor() {
    super(...arguments);
    /**
     * Indexes and values corresponding to them (entries) are stored in a certain order.
     *
     * @private
     * @type {Array<number>}
     */
    _defineProperty(this, "orderOfIndexes", []);
  }
  /**
   * Get full list of ordered values for particular indexes.
   *
   * @returns {Array}
   */
  getValues() {
    return this.orderOfIndexes.map(physicalIndex => this.indexedValues[physicalIndex]);
  }

  /**
   * Set new values for particular indexes. Entries are linked and stored in a certain order.
   *
   * Note: Please keep in mind that `change` hook triggered by the method may not update cache of a collection immediately.
   *
   * @param {Array} values List of set values.
   */
  setValues(values) {
    this.orderOfIndexes = [...Array(values.length).keys()];
    super.setValues(values);
  }

  /**
   * Set value at index and add it to the linked list of entries. Entries are stored in a certain order.
   *
   * Note: Value will be added at the end of the queue.
   *
   * @param {number} index The index.
   * @param {*} value The value to save.
   * @param {number} position Position to which entry will be added.
   *
   * @returns {boolean}
   */
  setValueAtIndex(index, value) {
    let position = arguments.length > 2 && arguments[2] !== undefined ? arguments[2] : this.orderOfIndexes.length;
    if (index < this.indexedValues.length) {
      this.indexedValues[index] = value;
      if (this.orderOfIndexes.includes(index) === false) {
        this.orderOfIndexes.splice(position, 0, index);
      }
      this.runLocalHooks('change');
      return true;
    }
    return false;
  }

  /**
   * Clear value for particular index.
   *
   * @param {number} physicalIndex Physical index.
   */
  clearValue(physicalIndex) {
    this.orderOfIndexes = getListWithoutIndexes(this.orderOfIndexes, [physicalIndex]);
    if (isFunction(this.initValueOrFn)) {
      super.setValueAtIndex(physicalIndex, this.initValueOrFn(physicalIndex));
    } else {
      super.setValueAtIndex(physicalIndex, this.initValueOrFn);
    }
  }

  /**
   * Get length of the index map.
   *
   * @returns {number}
   */
  getLength() {
    return this.orderOfIndexes.length;
  }

  /**
   * Set default values for elements from `0` to `n`, where `n` is equal to the handled variable.
   *
   * Note: Please keep in mind that `change` hook triggered by the method may not update cache of a collection immediately.
   *
   * @private
   * @param {number} [length] Length of list.
   */
  setDefaultValues() {
    let length = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : this.indexedValues.length;
    this.orderOfIndexes.length = 0;
    super.setDefaultValues(length);
  }

  /**
   * Add values to list and reorganize. It updates list of indexes related to ordered values.
   *
   * @private
   * @param {number} insertionIndex Position inside the list.
   * @param {Array} insertedIndexes List of inserted indexes.
   */
  insert(insertionIndex, insertedIndexes) {
    this.indexedValues = getListWithInsertedItems(this.indexedValues, insertionIndex, insertedIndexes, this.initValueOrFn);
    this.orderOfIndexes = getIncreasedIndexes(this.orderOfIndexes, insertedIndexes);
    super.insert(insertionIndex, insertedIndexes);
  }

  /**
   * Remove values from the list and reorganize. It updates list of indexes related to ordered values.
   *
   * @private
   * @param {Array} removedIndexes List of removed indexes.
   */
  remove(removedIndexes) {
    this.indexedValues = getListWithRemovedItems(this.indexedValues, removedIndexes);
    this.orderOfIndexes = getListWithoutIndexes(this.orderOfIndexes, removedIndexes);
    this.orderOfIndexes = getDecreasedIndexes(this.orderOfIndexes, removedIndexes);
    super.remove(removedIndexes);
  }

  /**
   * Get every entry containing index and value, respecting order of indexes.
   *
   * @returns {Array}
   */
  getEntries() {
    return this.orderOfIndexes.map(physicalIndex => [physicalIndex, this.getValueAtIndex(physicalIndex)]);
  }
}