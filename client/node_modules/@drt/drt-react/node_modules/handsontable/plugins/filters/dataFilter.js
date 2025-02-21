"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
require("core-js/modules/es.array.push.js");
var _array = require("../../helpers/array");
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
/**
 * @private
 * @class DataFilter
 */
class DataFilter {
  constructor(conditionCollection) {
    let columnDataFactory = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : () => [];
    /**
     * Reference to the instance of {ConditionCollection}.
     *
     * @type {ConditionCollection}
     */
    _defineProperty(this, "conditionCollection", void 0);
    /**
     * Function which provide source data factory for specified column.
     *
     * @type {Function}
     */
    _defineProperty(this, "columnDataFactory", void 0);
    this.conditionCollection = conditionCollection;
    this.columnDataFactory = columnDataFactory;
  }

  /**
   * Filter data based on the conditions collection.
   *
   * @returns {Array}
   */
  filter() {
    let filteredData = [];
    (0, _array.arrayEach)(this.conditionCollection.getFilteredColumns(), (physicalColumn, index) => {
      let columnData = this.columnDataFactory(physicalColumn);
      if (index) {
        columnData = this._getIntersectData(columnData, filteredData);
      }
      filteredData = this.filterByColumn(physicalColumn, columnData);
    });
    return filteredData;
  }

  /**
   * Filter data based on specified physical column index.
   *
   * @param {number} column The physical column index.
   * @param {Array} [dataSource] Data source as array of objects with `value` and `meta` keys (e.g. `{value: 'foo', meta: {}}`).
   * @returns {Array} Returns filtered data.
   */
  filterByColumn(column) {
    let dataSource = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : [];
    const filteredData = [];
    (0, _array.arrayEach)(dataSource, dataRow => {
      if (dataRow !== undefined && this.conditionCollection.isMatch(dataRow, column)) {
        filteredData.push(dataRow);
      }
    });
    return filteredData;
  }

  /**
   * Intersect data.
   *
   * @private
   * @param {Array} data The data to intersect.
   * @param {Array} needles The collection intersected rows with the data.
   * @returns {Array}
   */
  _getIntersectData(data, needles) {
    const result = [];
    (0, _array.arrayEach)(needles, needleRow => {
      const row = needleRow.meta.visualRow;
      if (data[row] !== undefined) {
        result[row] = data[row];
      }
    });
    return result;
  }
}
var _default = exports.default = DataFilter;