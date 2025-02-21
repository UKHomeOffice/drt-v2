import "core-js/modules/es.error.cause.js";
import "core-js/modules/es.array.push.js";
import "core-js/modules/esnext.iterator.constructor.js";
import "core-js/modules/esnext.iterator.some.js";
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
/**
 * The SelectionRange class is a simple CellRanges collection designed for easy manipulation of the multiple
 * consecutive and non-consecutive selections.
 *
 * @class SelectionRange
 * @util
 */
class SelectionRange {
  constructor(createCellRange) {
    /**
     * List of all CellRanges added to the class instance.
     *
     * @type {CellRange[]}
     */
    _defineProperty(this, "ranges", []);
    /**
     * @type {function(CellCoords): CellRange}
     */
    _defineProperty(this, "createCellRange", void 0);
    this.createCellRange = createCellRange;
  }

  /**
   * Check if selected range is empty.
   *
   * @returns {boolean}
   */
  isEmpty() {
    return this.size() === 0;
  }

  /**
   * Set coordinates to the class instance. It clears all previously added coordinates and push `coords`
   * to the collection.
   *
   * @param {CellCoords} coords The CellCoords instance with defined visual coordinates.
   * @returns {SelectionRange}
   */
  set(coords) {
    this.clear();
    this.ranges.push(this.createCellRange(coords));
    return this;
  }

  /**
   * Add coordinates to the class instance. The new coordinates are added to the end of the range collection.
   *
   * @param {CellCoords} coords The CellCoords instance with defined visual coordinates.
   * @returns {SelectionRange}
   */
  add(coords) {
    this.ranges.push(this.createCellRange(coords));
    return this;
  }

  /**
   * Removes from the stack the last added coordinates.
   *
   * @returns {SelectionRange}
   */
  pop() {
    this.ranges.pop();
    return this;
  }

  /**
   * Get last added coordinates from ranges, it returns a CellRange instance.
   *
   * @returns {CellRange|undefined}
   */
  current() {
    return this.peekByIndex(this.size() - 1);
  }

  /**
   * Get previously added coordinates from ranges, it returns a CellRange instance.
   *
   * @returns {CellRange|undefined}
   */
  previous() {
    return this.peekByIndex(this.size() - 2);
  }

  /**
   * Returns `true` if coords is within selection coords. This method iterates through all selection layers to check if
   * the coords object is within selection range.
   *
   * @param {CellCoords} coords The CellCoords instance with defined visual coordinates.
   * @returns {boolean}
   */
  includes(coords) {
    return this.ranges.some(cellRange => cellRange.includes(coords));
  }

  /**
   * Clear collection.
   *
   * @returns {SelectionRange}
   */
  clear() {
    this.ranges.length = 0;
    return this;
  }

  /**
   * Get count of added all coordinates added to the selection.
   *
   * @returns {number}
   */
  size() {
    return this.ranges.length;
  }

  /**
   * Peek the coordinates based on the index where that coordinate resides in the collection.
   *
   * @param {number} [index=0] An index where the coordinate will be retrieved from. The index '0' gets the
   * latest range.
   * @returns {CellRange|undefined}
   */
  peekByIndex() {
    let index = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : 0;
    let cellRange;
    if (index >= 0 && index < this.size()) {
      cellRange = this.ranges[index];
    }
    return cellRange;
  }
  [Symbol.iterator]() {
    return this.ranges[Symbol.iterator]();
  }
}
export default SelectionRange;