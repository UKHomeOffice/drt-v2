"use strict";

exports.__esModule = true;
require("core-js/modules/es.array.push.js");
/**
 * @class Stack
 * @util
 */
class Stack {
  constructor() {
    let initial = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : [];
    /**
     * Items collection.
     *
     * @type {Array}
     */
    this.items = initial;
  }

  /**
   * Add new item or items at the back of the stack.
   *
   * @param {*} items An item to add.
   */
  push() {
    this.items.push(...arguments);
  }

  /**
   * Remove the last element from the stack and returns it.
   *
   * @returns {*}
   */
  pop() {
    return this.items.pop();
  }

  /**
   * Return the last element from the stack (without modification stack).
   *
   * @returns {*}
   */
  peek() {
    return this.isEmpty() ? undefined : this.items[this.items.length - 1];
  }

  /**
   * Check if the stack is empty.
   *
   * @returns {boolean}
   */
  isEmpty() {
    return !this.size();
  }

  /**
   * Return number of elements in the stack.
   *
   * @returns {number}
   */
  size() {
    return this.items.length;
  }
}
var _default = exports.default = Stack;