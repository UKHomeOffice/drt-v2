"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
require("core-js/modules/es.array.push.js");
var _number = require("../../helpers/number");
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
/**
 * @private
 */
class DataProvider {
  constructor(hotInstance) {
    /**
     * Handsontable instance.
     *
     * @type {Core}
     */
    _defineProperty(this, "hot", void 0);
    /**
     * Format type class options.
     *
     * @type {object}
     */
    _defineProperty(this, "options", {});
    this.hot = hotInstance;
  }

  /**
   * Set options for data provider.
   *
   * @param {object} options Object with specified options.
   */
  setOptions(options) {
    this.options = options;
  }

  /**
   * Get table data based on provided settings to the class constructor.
   *
   * @returns {Array}
   */
  getData() {
    const {
      startRow,
      startCol,
      endRow,
      endCol
    } = this._getDataRange();
    const options = this.options;
    const data = [];
    (0, _number.rangeEach)(startRow, endRow, rowIndex => {
      const row = [];
      if (!options.exportHiddenRows && this._isHiddenRow(rowIndex)) {
        return;
      }
      (0, _number.rangeEach)(startCol, endCol, colIndex => {
        if (!options.exportHiddenColumns && this._isHiddenColumn(colIndex)) {
          return;
        }
        row.push(this.hot.getDataAtCell(rowIndex, colIndex));
      });
      data.push(row);
    });
    return data;
  }

  /**
   * Gets list of row headers.
   *
   * @returns {Array}
   */
  getRowHeaders() {
    const headers = [];
    if (this.options.rowHeaders) {
      const {
        startRow,
        endRow
      } = this._getDataRange();
      const rowHeaders = this.hot.getRowHeader();
      (0, _number.rangeEach)(startRow, endRow, row => {
        if (!this.options.exportHiddenRows && this._isHiddenRow(row)) {
          return;
        }
        headers.push(rowHeaders[row]);
      });
    }
    return headers;
  }

  /**
   * Gets list of columns headers.
   *
   * @returns {Array}
   */
  getColumnHeaders() {
    const headers = [];
    if (this.options.columnHeaders) {
      const {
        startCol,
        endCol
      } = this._getDataRange();
      const colHeaders = this.hot.getColHeader();
      (0, _number.rangeEach)(startCol, endCol, column => {
        if (!this.options.exportHiddenColumns && this._isHiddenColumn(column)) {
          return;
        }
        headers.push(colHeaders[column]);
      });
    }
    return headers;
  }

  /**
   * Get data range object based on settings provided in the class constructor.
   *
   * @private
   * @returns {object} Returns object with keys `startRow`, `startCol`, `endRow` and `endCol`.
   */
  _getDataRange() {
    const cols = this.hot.countCols() - 1;
    const rows = this.hot.countRows() - 1;
    let [startRow = 0, startCol = 0, endRow = rows, endCol = cols] = this.options.range;
    startRow = Math.max(startRow, 0);
    startCol = Math.max(startCol, 0);
    endRow = Math.min(endRow, rows);
    endCol = Math.min(endCol, cols);
    return {
      startRow,
      startCol,
      endRow,
      endCol
    };
  }

  /**
   * Check if row at specified row index is hidden.
   *
   * @private
   * @param {number} row Row index.
   * @returns {boolean}
   */
  _isHiddenRow(row) {
    return this.hot.rowIndexMapper.isHidden(this.hot.toPhysicalRow(row));
  }

  /**
   * Check if column at specified column index is hidden.
   *
   * @private
   * @param {number} column Visual column index.
   * @returns {boolean}
   */
  _isHiddenColumn(column) {
    return this.hot.columnIndexMapper.isHidden(this.hot.toPhysicalColumn(column));
  }
}
var _default = exports.default = DataProvider;