import "core-js/modules/es.error.cause.js";
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
/**
 * Refactored implementation of LinkedList (part of javascript-algorithms project) by Github users:
 * mgechev, AndriiHeonia, Microfed and Jakeh (part of javascript-algorithms project - all project contributors
 * at repository website).
 *
 * Link to repository: https://github.com/mgechev/javascript-algorithms.
 */

/**
 * Linked list node.
 *
 * @class NodeStructure
 * @util
 */
class NodeStructure {
  constructor(data) {
    /**
     * Data of the node.
     *
     * @member {object}
     */
    _defineProperty(this, "data", void 0);
    /**
     * Next node.
     *
     * @member {NodeStructure}
     */
    _defineProperty(this, "next", null);
    /**
     * Previous node.
     *
     * @member {NodeStructure}
     */
    _defineProperty(this, "prev", null);
    this.data = data;
  }
}

/**
 * Linked list.
 *
 * @class LinkedList
 * @util
 */
class LinkedList {
  constructor() {
    _defineProperty(this, "first", null);
    _defineProperty(this, "last", null);
  }
  /**
   * Add data to the end of linked list.
   *
   * @param {object} data Data which should be added.
   * @returns {NodeStructure} Returns the node which has been added.
   */
  push(data) {
    const node = new NodeStructure(data);
    if (this.first === null) {
      this.first = node;
      this.last = node;
    } else {
      const temp = this.last;
      this.last = node;
      node.prev = temp;
      temp.next = node;
    }
    return node;
  }

  /**
   * Add data to the beginning of linked list.
   *
   * @param {object} data Data which should be added.
   */
  unshift(data) {
    const node = new NodeStructure(data);
    if (this.first === null) {
      this.first = node;
      this.last = node;
    } else {
      const temp = this.first;
      this.first = node;
      node.next = temp;
      temp.prev = node;
    }
  }

  /**
   * In order traversal of the linked list.
   *
   * @param {Function} callback Callback which should be executed on each node.
   */
  inorder(callback) {
    let temp = this.first;
    while (temp) {
      const interrupt = callback(temp);
      if (temp === this.last || interrupt === true) {
        break;
      }
      temp = temp.next;
    }
  }

  /**
   * Remove data from the linked list.
   *
   * @param {object} data Data which should be removed.
   * @returns {boolean} Returns true if data has been removed.
   */
  remove(data) {
    if (this.first === null) {
      return false;
    }
    let temp = this.first;
    let next;
    let prev;
    while (temp) {
      if (temp.data === data) {
        next = temp.next;
        prev = temp.prev;
        if (next) {
          next.prev = prev;
        }
        if (prev) {
          prev.next = next;
        }
        if (temp === this.first) {
          this.first = next;
        }
        if (temp === this.last) {
          this.last = prev;
        }
        return true;
      }
      temp = temp.next;
    }
    return false;
  }

  /**
   * Check if linked list contains cycle.
   *
   * @returns {boolean} Returns true if linked list contains cycle.
   */
  hasCycle() {
    let fast = this.first;
    let slow = this.first;
    while (true) {
      if (fast === null) {
        return false;
      }
      fast = fast.next;
      if (fast === null) {
        return false;
      }
      fast = fast.next;
      slow = slow.next;
      if (fast === slow) {
        return true;
      }
    }
  }

  /**
   * Return last node from the linked list.
   *
   * @returns {NodeStructure} Last node.
   */
  pop() {
    if (this.last === null) {
      return null;
    }
    const temp = this.last;
    this.last = this.last.prev;
    return temp;
  }

  /**
   * Return first node from the linked list.
   *
   * @returns {NodeStructure} First node.
   */
  shift() {
    if (this.first === null) {
      return null;
    }
    const temp = this.first;
    this.first = this.first.next;
    return temp;
  }

  /**
   * Reverses the linked list recursively.
   */
  recursiveReverse() {
    /**
     * @param {*} current The current value.
     * @param {*} next The next value.
     */
    function inverse(current, next) {
      if (!next) {
        return;
      }
      inverse(next, next.next);
      next.next = current;
    }
    if (!this.first) {
      return;
    }
    inverse(this.first, this.first.next);
    this.first.next = null;
    const temp = this.first;
    this.first = this.last;
    this.last = temp;
  }

  /**
   * Reverses the linked list iteratively.
   */
  reverse() {
    if (!this.first || !this.first.next) {
      return;
    }
    let current = this.first.next;
    let prev = this.first;
    let temp;
    while (current) {
      temp = current.next;
      current.next = prev;
      prev.prev = current;
      prev = current;
      current = temp;
    }
    this.first.next = null;
    this.last.prev = null;
    temp = this.first;
    this.first = prev;
    this.last = temp;
  }
}
export { NodeStructure };
export default LinkedList;