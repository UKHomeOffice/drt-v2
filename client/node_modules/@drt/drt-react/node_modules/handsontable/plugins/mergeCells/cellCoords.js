"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
var _templateLiteralTag = require("../../helpers/templateLiteralTag");
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
/**
 * The `MergedCellCoords` class represents a single merged cell.
 *
 * @private
 * @class MergedCellCoords
 */
var _cellRange = /*#__PURE__*/new WeakMap();
class MergedCellCoords {
  constructor(row, column, rowspan, colspan, cellCoordsFactory, cellRangeFactory) {
    /**
     * The index of the topmost merged cell row.
     *
     * @type {number}
     */
    _defineProperty(this, "row", void 0);
    /**
     * The index of the leftmost column.
     *
     * @type {number}
     */
    _defineProperty(this, "col", void 0);
    /**
     * The `rowspan` value of the merged cell.
     *
     * @type {number}
     */
    _defineProperty(this, "rowspan", void 0);
    /**
     * The `colspan` value of the merged cell.
     *
     * @type {number}
     */
    _defineProperty(this, "colspan", void 0);
    /**
     * `true` only if the merged cell is bound to be removed.
     *
     * @type {boolean}
     */
    _defineProperty(this, "removed", false);
    /**
     * The CellCoords function factory.
     *
     * @type {Function}
     */
    _defineProperty(this, "cellCoordsFactory", void 0);
    /**
     * The CellRange function factory.
     *
     * @type {Function}
     */
    _defineProperty(this, "cellRangeFactory", void 0);
    /**
     * The cached range coordinates of the merged cell.
     *
     * @type {CellRange}
     */
    _classPrivateFieldInitSpec(this, _cellRange, null);
    this.row = row;
    this.col = column;
    this.rowspan = rowspan;
    this.colspan = colspan;
    this.cellCoordsFactory = cellCoordsFactory;
    this.cellRangeFactory = cellRangeFactory;
  }

  /**
   * Get a warning message for when the declared merged cell data contains negative values.
   *
   * @param {{ row: number, col: number, rowspan: number, colspan: number }} mergedCell Object containing information
   * about the merged cells that was about to be added.
   * @returns {string}
   */
  static NEGATIVE_VALUES_WARNING(_ref) {
    let {
      row,
      col,
      rowspan,
      colspan
    } = _ref;
    return (0, _templateLiteralTag.toSingleLine)`The merged cell declared with {row: ${row}, col: ${col},\x20
      rowspan: ${rowspan}, colspan: ${colspan}} contains negative values, which is\x20
      not supported. It will not be added to the collection.`;
  }

  /**
   * Get a warning message for when the declared merged cell data contains values exceeding the table limits.
   *
   * @param {{ row: number, col: number, rowspan: number, colspan: number }} mergedCell Object containing information
   * about the merged cells that was about to be added.
   * @returns {string}
   */
  static IS_OUT_OF_BOUNDS_WARNING(_ref2) {
    let {
      row,
      col
    } = _ref2;
    return (0, _templateLiteralTag.toSingleLine)`The merged cell declared at [${row}, ${col}] is positioned\x20
      (or positioned partially) outside of the table range. It was not added to the table, please fix your setup.`;
  }

  /**
   * Get a warning message for when the declared merged cell data represents a single cell.
   *
   * @param {{ row: number, col: number, rowspan: number, colspan: number }} mergedCell Object containing information
   * about the merged cells that was about to be added.
   * @returns {string}
   */
  static IS_SINGLE_CELL(_ref3) {
    let {
      row,
      col
    } = _ref3;
    return (0, _templateLiteralTag.toSingleLine)`The merged cell declared at [${row}, ${col}] has both "rowspan"\x20
      and "colspan" declared as "1", which makes it a single cell. It cannot be added to the collection.`;
  }

  /**
   * Get a warning message for when the declared merged cell data contains "colspan" or "rowspan", that equals 0.
   *
   * @param {{ row: number, col: number, rowspan: number, colspan: number }} mergedCell Object containing information
   * about the merged cells that was about to be added.
   * @returns {string}
   */
  static ZERO_SPAN_WARNING(_ref4) {
    let {
      row,
      col
    } = _ref4;
    return (0, _templateLiteralTag.toSingleLine)`The merged cell declared at [${row}, ${col}] has "rowspan"\x20
      or "colspan" declared as "0", which is not supported. It cannot be added to the collection.`;
  }

  /**
   * Check whether the values provided for a merged cell contain any negative values.
   *
   * @param {{ row: number, col: number, rowspan: number, colspan: number }} mergedCell Object containing information
   * about the merged cells that was about to be added.
   * @returns {boolean}
   */
  static containsNegativeValues(_ref5) {
    let {
      row,
      col,
      rowspan,
      colspan
    } = _ref5;
    return row < 0 || col < 0 || rowspan < 0 || colspan < 0;
  }

  /**
   * Check whether the provided merged cell information object represents a single cell.
   *
   * @private
   * @param {{ row: number, col: number, rowspan: number, colspan: number }} mergedCell Object containing information
   * about the merged cells that was about to be added.
   * @returns {boolean}
   */
  static isSingleCell(_ref6) {
    let {
      rowspan,
      colspan
    } = _ref6;
    return colspan === 1 && rowspan === 1;
  }

  /**
   * Check whether the provided merged cell information object contains a rowspan or colspan of 0.
   *
   * @private
   * @param {{ row: number, col: number, rowspan: number, colspan: number }} mergedCell Object containing information
   * about the merged cells that was about to be added.
   * @returns {boolean}
   */
  static containsZeroSpan(_ref7) {
    let {
      rowspan,
      colspan
    } = _ref7;
    return colspan === 0 || rowspan === 0;
  }

  /**
   * Check whether the provided merged cell object is to be declared out of bounds of the table.
   *
   * @param {object} mergeCell Object containing the `row`, `col`, `rowspan` and `colspan` properties.
   * @param {number} rowCount Number of rows in the table.
   * @param {number} columnCount Number of rows in the table.
   * @returns {boolean}
   */
  static isOutOfBounds(mergeCell, rowCount, columnCount) {
    return mergeCell.row < 0 || mergeCell.col < 0 || mergeCell.row >= rowCount || mergeCell.row + mergeCell.rowspan - 1 >= rowCount || mergeCell.col >= columnCount || mergeCell.col + mergeCell.colspan - 1 >= columnCount;
  }

  /**
   * Sanitize (prevent from going outside the boundaries) the merged cell.
   *
   * @param {Core} hotInstance The Handsontable instance.
   */
  normalize(hotInstance) {
    const totalRows = hotInstance.countRows();
    const totalColumns = hotInstance.countCols();
    if (this.row < 0) {
      this.row = 0;
    } else if (this.row > totalRows - 1) {
      this.row = totalRows - 1;
    }
    if (this.col < 0) {
      this.col = 0;
    } else if (this.col > totalColumns - 1) {
      this.col = totalColumns - 1;
    }
    if (this.row + this.rowspan > totalRows - 1) {
      this.rowspan = totalRows - this.row;
    }
    if (this.col + this.colspan > totalColumns - 1) {
      this.colspan = totalColumns - this.col;
    }
    _classPrivateFieldSet(_cellRange, this, null);
  }

  /**
   * Returns `true` if the provided coordinates are inside the merged cell.
   *
   * @param {number} row The row index.
   * @param {number} column The column index.
   * @returns {boolean}
   */
  includes(row, column) {
    return this.row <= row && this.col <= column && this.row + this.rowspan - 1 >= row && this.col + this.colspan - 1 >= column;
  }

  /**
   * Returns `true` if the provided `column` property is within the column span of the merged cell.
   *
   * @param {number} column The column index.
   * @returns {boolean}
   */
  includesHorizontally(column) {
    return this.col <= column && this.col + this.colspan - 1 >= column;
  }

  /**
   * Returns `true` if the provided `row` property is within the row span of the merged cell.
   *
   * @param {number} row Row index.
   * @returns {boolean}
   */
  includesVertically(row) {
    return this.row <= row && this.row + this.rowspan - 1 >= row;
  }

  /**
   * Shift (and possibly resize, if needed) the merged cell.
   *
   * @param {Array} shiftVector 2-element array containing the information on the shifting in the `x` and `y` axis.
   * @param {number} indexOfChange Index of the preceding change.
   * @returns {boolean} Returns `false` if the whole merged cell was removed.
   */
  shift(shiftVector, indexOfChange) {
    const shiftValue = shiftVector[0] || shiftVector[1];
    const shiftedIndex = indexOfChange + Math.abs(shiftVector[0] || shiftVector[1]) - 1;
    const span = shiftVector[0] ? 'colspan' : 'rowspan';
    const index = shiftVector[0] ? 'col' : 'row';
    const changeStart = Math.min(indexOfChange, shiftedIndex);
    const changeEnd = Math.max(indexOfChange, shiftedIndex);
    const mergeStart = this[index];
    const mergeEnd = this[index] + this[span] - 1;
    if (mergeStart >= indexOfChange) {
      this[index] += shiftValue;
    }

    // adding rows/columns
    if (shiftValue > 0) {
      if (indexOfChange <= mergeEnd && indexOfChange > mergeStart) {
        this[span] += shiftValue;
      }

      // removing rows/columns
    } else if (shiftValue < 0) {
      // removing the whole merge
      if (changeStart <= mergeStart && changeEnd >= mergeEnd) {
        this.removed = true;
        _classPrivateFieldSet(_cellRange, this, null);
        return false;

        // removing the merge partially, including the beginning
      } else if (mergeStart >= changeStart && mergeStart <= changeEnd) {
        const removedOffset = changeEnd - mergeStart + 1;
        const preRemovedOffset = Math.abs(shiftValue) - removedOffset;
        this[index] -= preRemovedOffset + shiftValue;
        this[span] -= removedOffset;

        // removing the middle part of the merge
      } else if (mergeStart <= changeStart && mergeEnd >= changeEnd) {
        this[span] += shiftValue;

        // removing the end part of the merge
      } else if (mergeStart <= changeStart && mergeEnd >= changeStart && mergeEnd < changeEnd) {
        const removedPart = mergeEnd - changeStart + 1;
        this[span] -= removedPart;
      }
    }
    _classPrivateFieldSet(_cellRange, this, null);
    return true;
  }

  /**
   * Check if the second provided merged cell is "farther" in the provided direction.
   *
   * @param {MergedCellCoords} mergedCell The merged cell to check.
   * @param {string} direction Drag direction.
   * @returns {boolean|null} `true` if the second provided merged cell is "farther".
   */
  isFarther(mergedCell, direction) {
    if (!mergedCell) {
      return true;
    }
    if (direction === 'down') {
      return mergedCell.row + mergedCell.rowspan - 1 < this.row + this.rowspan - 1;
    } else if (direction === 'up') {
      return mergedCell.row > this.row;
    } else if (direction === 'right') {
      return mergedCell.col + mergedCell.colspan - 1 < this.col + this.colspan - 1;
    } else if (direction === 'left') {
      return mergedCell.col > this.col;
    }
    return null;
  }

  /**
   * Get the bottom row index of the merged cell.
   *
   * @returns {number}
   */
  getLastRow() {
    return this.row + this.rowspan - 1;
  }

  /**
   * Get the rightmost column index of the merged cell.
   *
   * @returns {number}
   */
  getLastColumn() {
    return this.col + this.colspan - 1;
  }

  /**
   * Get the range coordinates of the merged cell.
   *
   * @returns {CellRange}
   */
  getRange() {
    if (!_classPrivateFieldGet(_cellRange, this)) {
      _classPrivateFieldSet(_cellRange, this, this.cellRangeFactory(this.cellCoordsFactory(this.row, this.col), this.cellCoordsFactory(this.row, this.col), this.cellCoordsFactory(this.getLastRow(), this.getLastColumn())));
    }
    return _classPrivateFieldGet(_cellRange, this);
  }
}
var _default = exports.default = MergedCellCoords;