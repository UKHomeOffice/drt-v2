"use strict";

exports.__esModule = true;
exports.createUniqueSet = createUniqueSet;
require("core-js/modules/es.error.cause.js");
require("core-js/modules/es.set.difference.v2.js");
require("core-js/modules/es.set.intersection.v2.js");
require("core-js/modules/es.set.is-disjoint-from.v2.js");
require("core-js/modules/es.set.is-subset-of.v2.js");
require("core-js/modules/es.set.is-superset-of.v2.js");
require("core-js/modules/es.set.symmetric-difference.v2.js");
require("core-js/modules/es.set.union.v2.js");
var _function = require("../../helpers/function");
const DEFAULT_ERROR_ITEM_EXISTS = item => `'${item}' value is already declared in a unique set.`;

/**
 * @typedef {object} UniqueSet
 * @property {Function} addItem Adds items to the priority set.
 * @property {Function} getItems Gets items from the set in order of addition.
 */
/**
 * Creates a new unique set.
 *
 * @param {object} config The config for priority set.
 * @param {Function} config.errorItemExists The function to generate custom error message if item is already in the set.
 * @returns {UniqueSet}
 */
function createUniqueSet() {
  let {
    errorItemExists
  } = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : {};
  const uniqueSet = new Set();
  errorItemExists = (0, _function.isFunction)(errorItemExists) ? errorItemExists : DEFAULT_ERROR_ITEM_EXISTS;

  /**
   * Adds items to the unique set. Throws an error if `item` is already added.
   *
   * @param {*} item The adding item.
   */
  function addItem(item) {
    if (uniqueSet.has(item)) {
      throw new Error(errorItemExists(item));
    }
    uniqueSet.add(item);
  }

  /**
   * Gets items from the set in order of addition.
   *
   * @returns {*}
   */
  function getItems() {
    return [...uniqueSet];
  }

  /**
   * Clears the unique set.
   */
  function clear() {
    uniqueSet.clear();
  }
  return {
    addItem,
    clear,
    getItems
  };
}