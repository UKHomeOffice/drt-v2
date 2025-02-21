import "core-js/modules/es.error.cause.js";
import "core-js/modules/es.array.push.js";
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
import { arrayEach } from "../../helpers/array.mjs";
import { rangeEach } from "../../helpers/number.mjs";
/**
 * The utils class produces the selection ranges in the `{startRow, startCol, endRow, endCol}` format
 * based on the current table selection. The CopyPaste plugin consumes that ranges to generate
 * appropriate data ready to copy to the clipboard.
 *
 * @private
 */
var _selectedRange = /*#__PURE__*/new WeakMap();
var _countRows = /*#__PURE__*/new WeakMap();
var _countColumns = /*#__PURE__*/new WeakMap();
var _rowsLimit = /*#__PURE__*/new WeakMap();
var _columnsLimit = /*#__PURE__*/new WeakMap();
var _countColumnHeaders = /*#__PURE__*/new WeakMap();
var _CopyableRangesFactory_brand = /*#__PURE__*/new WeakSet();
export class CopyableRangesFactory {
  /* eslint-disable jsdoc/require-description-complete-sentence */
  /**
   * @param {{
   *   countRows: function(): number,
   *   countColumns: function(): number,
   *   rowsLimit: function(): number,
   *   columnsLimit: function(): number,
   *   countColumnHeaders: function(): number
   * }} dependencies The utils class dependencies.
   */
  constructor(_ref) {
    let {
      countRows,
      countColumns,
      rowsLimit,
      columnsLimit,
      countColumnHeaders
    } = _ref;
    /**
     * Trimmed the columns range to the limit.
     *
     * @param {*} startColumn The lowest column index in the range.
     * @param {*} endColumn The highest column index in the range.
     * @returns {number} Returns trimmed column index if it exceeds the limit.
     */
    _classPrivateMethodInitSpec(this, _CopyableRangesFactory_brand);
    /**
     * @type {CellRange}
     */
    _classPrivateFieldInitSpec(this, _selectedRange, void 0);
    /**
     * @type {function(): number}
     */
    _classPrivateFieldInitSpec(this, _countRows, void 0);
    /**
     * @type {function(): number}
     */
    _classPrivateFieldInitSpec(this, _countColumns, void 0);
    /**
     * @type {function(): number}
     */
    _classPrivateFieldInitSpec(this, _rowsLimit, void 0);
    /**
     * @type {function(): number}
     */
    _classPrivateFieldInitSpec(this, _columnsLimit, void 0);
    /**
     * @type {function(): number}
     */
    _classPrivateFieldInitSpec(this, _countColumnHeaders, void 0);
    _classPrivateFieldSet(_countRows, this, countRows);
    _classPrivateFieldSet(_countColumns, this, countColumns);
    _classPrivateFieldSet(_rowsLimit, this, rowsLimit);
    _classPrivateFieldSet(_columnsLimit, this, columnsLimit);
    _classPrivateFieldSet(_countColumnHeaders, this, countColumnHeaders);
  }
  /* eslint-enable jsdoc/require-description-complete-sentence */

  /**
   * Sets the selection range to be processed.
   *
   * @param {CellRange} selectedRange The selection range represented by the CellRange class.
   */
  setSelectedRange(selectedRange) {
    _classPrivateFieldSet(_selectedRange, this, selectedRange);
  }

  /**
   * Returns a new coords object within the dataset range (cells) with `startRow`, `startCol`, `endRow`
   * and `endCol` keys.
   *
   * @returns {{startRow: number, startCol: number, endRow: number, endCol: number} | null}
   */
  getCellsRange() {
    if (_classPrivateFieldGet(_countRows, this).call(this) === 0 || _classPrivateFieldGet(_countColumns, this).call(this) === 0) {
      return null;
    }
    const {
      row: startRow,
      col: startCol
    } = _classPrivateFieldGet(_selectedRange, this).getTopStartCorner();
    const {
      row: endRow,
      col: endCol
    } = _classPrivateFieldGet(_selectedRange, this).getBottomEndCorner();
    const finalEndRow = _assertClassBrand(_CopyableRangesFactory_brand, this, _trimRowsRange).call(this, startRow, endRow);
    const finalEndCol = _assertClassBrand(_CopyableRangesFactory_brand, this, _trimColumnsRange).call(this, startCol, endCol);
    const isRangeTrimmed = endRow !== finalEndRow || endCol !== finalEndCol;
    return {
      isRangeTrimmed,
      startRow,
      startCol,
      endRow: finalEndRow,
      endCol: finalEndCol
    };
  }

  /**
   * Returns a new coords object within the most-bottom column headers range with `startRow`,
   * `startCol`, `endRow` and `endCol` keys.
   *
   * @returns {{startRow: number, startCol: number, endRow: number, endCol: number} | null}
   */
  getMostBottomColumnHeadersRange() {
    if (_classPrivateFieldGet(_countColumns, this).call(this) === 0 || _classPrivateFieldGet(_countColumnHeaders, this).call(this) === 0) {
      return null;
    }
    const {
      col: startCol
    } = _classPrivateFieldGet(_selectedRange, this).getTopStartCorner();
    const {
      col: endCol
    } = _classPrivateFieldGet(_selectedRange, this).getBottomEndCorner();
    const finalEndCol = _assertClassBrand(_CopyableRangesFactory_brand, this, _trimColumnsRange).call(this, startCol, endCol);
    const isRangeTrimmed = endCol !== finalEndCol;
    return {
      isRangeTrimmed,
      startRow: -1,
      startCol,
      endRow: -1,
      endCol: finalEndCol
    };
  }

  /**
   * Returns a new coords object within all column headers layers (including nested headers) range with
   * `startRow`, `startCol`, `endRow` and `endCol` keys.
   *
   * @returns {{startRow: number, startCol: number, endRow: number, endCol: number} | null}
   */
  getAllColumnHeadersRange() {
    if (_classPrivateFieldGet(_countColumns, this).call(this) === 0 || _classPrivateFieldGet(_countColumnHeaders, this).call(this) === 0) {
      return null;
    }
    const {
      col: startCol
    } = _classPrivateFieldGet(_selectedRange, this).getTopStartCorner();
    const {
      col: endCol
    } = _classPrivateFieldGet(_selectedRange, this).getBottomEndCorner();
    const finalEndCol = _assertClassBrand(_CopyableRangesFactory_brand, this, _trimColumnsRange).call(this, startCol, endCol);
    const isRangeTrimmed = endCol !== finalEndCol;
    return {
      isRangeTrimmed,
      startRow: -_classPrivateFieldGet(_countColumnHeaders, this).call(this),
      startCol,
      endRow: -1,
      endCol: finalEndCol
    };
  }
}

/**
 * Returns an object with `rows` and `columns` keys. The arrays contains sorted indexes
 * generated according to the given `ranges` array.
 *
 * @param {Array<{startRow: number, startCol: number, endRow: number, endCol: number}>} ranges The range to process.
 * @returns {{rows: number[], columns: number[]}}
 */
function _trimColumnsRange(startColumn, endColumn) {
  return Math.min(endColumn, Math.max(startColumn + _classPrivateFieldGet(_columnsLimit, this).call(this) - 1, startColumn));
}
/**
 * Trimmed the rows range to the limit.
 *
 * @param {*} startRow The lowest row index in the range.
 * @param {*} endRow The highest row index in the range.
 * @returns {number} Returns trimmed row index if it exceeds the limit.
 */
function _trimRowsRange(startRow, endRow) {
  return Math.min(endRow, Math.max(startRow + _classPrivateFieldGet(_rowsLimit, this).call(this) - 1, startRow));
}
export function normalizeRanges(ranges) {
  const rows = [];
  const columns = [];
  arrayEach(ranges, range => {
    const minRow = Math.min(range.startRow, range.endRow);
    const maxRow = Math.max(range.startRow, range.endRow);
    rangeEach(minRow, maxRow, row => {
      if (rows.indexOf(row) === -1) {
        rows.push(row);
      }
    });
    const minColumn = Math.min(range.startCol, range.endCol);
    const maxColumn = Math.max(range.startCol, range.endCol);
    rangeEach(minColumn, maxColumn, column => {
      if (columns.indexOf(column) === -1) {
        columns.push(column);
      }
    });
  });
  return {
    rows,
    columns
  };
}