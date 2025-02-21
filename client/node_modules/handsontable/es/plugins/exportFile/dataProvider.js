import "core-js/modules/es.symbol";
import "core-js/modules/es.symbol.description";
import "core-js/modules/es.symbol.iterator";
import "core-js/modules/es.array.iterator";
import "core-js/modules/es.object.to-string";
import "core-js/modules/es.regexp.to-string";
import "core-js/modules/es.string.iterator";
import "core-js/modules/web.dom-collections.iterator";

function _slicedToArray(arr, i) { return _arrayWithHoles(arr) || _iterableToArrayLimit(arr, i) || _nonIterableRest(); }

function _nonIterableRest() { throw new TypeError("Invalid attempt to destructure non-iterable instance"); }

function _iterableToArrayLimit(arr, i) { if (!(Symbol.iterator in Object(arr) || Object.prototype.toString.call(arr) === "[object Arguments]")) { return; } var _arr = []; var _n = true; var _d = false; var _e = undefined; try { for (var _i = arr[Symbol.iterator](), _s; !(_n = (_s = _i.next()).done); _n = true) { _arr.push(_s.value); if (i && _arr.length === i) break; } } catch (err) { _d = true; _e = err; } finally { try { if (!_n && _i["return"] != null) _i["return"](); } finally { if (_d) throw _e; } } return _arr; }

function _arrayWithHoles(arr) { if (Array.isArray(arr)) return arr; }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

import { rangeEach } from '../../helpers/number'; // Waiting for jshint >=2.9.0 where they added support for destructing
// jshint ignore: start

/**
 * @plugin ExportFile
 * @private
 */

var DataProvider =
/*#__PURE__*/
function () {
  function DataProvider(hotInstance) {
    _classCallCheck(this, DataProvider);

    /**
     * Handsontable instance.
     *
     * @type {Core}
     */
    this.hot = hotInstance;
    /**
     * Format type class options.
     *
     * @type {Object}
     */

    this.options = {};
  }
  /**
   * Set options for data provider.
   *
   * @param {Object} options Object with specified options.
   */


  _createClass(DataProvider, [{
    key: "setOptions",
    value: function setOptions(options) {
      this.options = options;
    }
    /**
     * Get table data based on provided settings to the class constructor.
     *
     * @returns {Array}
     */

  }, {
    key: "getData",
    value: function getData() {
      var _this = this;

      var _this$_getDataRange = this._getDataRange(),
          startRow = _this$_getDataRange.startRow,
          startCol = _this$_getDataRange.startCol,
          endRow = _this$_getDataRange.endRow,
          endCol = _this$_getDataRange.endCol;

      var options = this.options;
      var data = [];
      rangeEach(startRow, endRow, function (rowIndex) {
        var row = [];

        if (!options.exportHiddenRows && _this._isHiddenRow(rowIndex)) {
          return;
        }

        rangeEach(startCol, endCol, function (colIndex) {
          if (!options.exportHiddenColumns && _this._isHiddenColumn(colIndex)) {
            return;
          }

          row.push(_this.hot.getDataAtCell(rowIndex, colIndex));
        });
        data.push(row);
      });
      return data;
    }
    /**
     * Gets list of row headers.
     *
     * @return {Array}
     */

  }, {
    key: "getRowHeaders",
    value: function getRowHeaders() {
      var _this2 = this;

      var headers = [];

      if (this.options.rowHeaders) {
        var _this$_getDataRange2 = this._getDataRange(),
            startRow = _this$_getDataRange2.startRow,
            endRow = _this$_getDataRange2.endRow;

        var rowHeaders = this.hot.getRowHeader();
        rangeEach(startRow, endRow, function (row) {
          if (!_this2.options.exportHiddenRows && _this2._isHiddenRow(row)) {
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
     * @return {Array}
     */

  }, {
    key: "getColumnHeaders",
    value: function getColumnHeaders() {
      var _this3 = this;

      var headers = [];

      if (this.options.columnHeaders) {
        var _this$_getDataRange3 = this._getDataRange(),
            startCol = _this$_getDataRange3.startCol,
            endCol = _this$_getDataRange3.endCol;

        var colHeaders = this.hot.getColHeader();
        rangeEach(startCol, endCol, function (column) {
          if (!_this3.options.exportHiddenColumns && _this3._isHiddenColumn(column)) {
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
     * @returns {Object} Returns object with keys `startRow`, `startCol`, `endRow` and `endCol`.
     */

  }, {
    key: "_getDataRange",
    value: function _getDataRange() {
      var cols = this.hot.countCols() - 1;
      var rows = this.hot.countRows() - 1;

      var _this$options$range = _slicedToArray(this.options.range, 4),
          _this$options$range$ = _this$options$range[0],
          startRow = _this$options$range$ === void 0 ? 0 : _this$options$range$,
          _this$options$range$2 = _this$options$range[1],
          startCol = _this$options$range$2 === void 0 ? 0 : _this$options$range$2,
          _this$options$range$3 = _this$options$range[2],
          endRow = _this$options$range$3 === void 0 ? rows : _this$options$range$3,
          _this$options$range$4 = _this$options$range[3],
          endCol = _this$options$range$4 === void 0 ? cols : _this$options$range$4;

      startRow = Math.max(startRow, 0);
      startCol = Math.max(startCol, 0);
      endRow = Math.min(endRow, rows);
      endCol = Math.min(endCol, cols);
      return {
        startRow: startRow,
        startCol: startCol,
        endRow: endRow,
        endCol: endCol
      };
    }
    /**
     * Check if row at specified row index is hidden.
     *
     * @private
     * @param {Number} row Row index.
     * @returns {Boolean}
     */

  }, {
    key: "_isHiddenRow",
    value: function _isHiddenRow(row) {
      return this.hot.hasHook('hiddenRow') && this.hot.runHooks('hiddenRow', row);
    }
    /**
     * Check if column at specified column index is hidden.
     *
     * @private
     * @param {Number} column Column index.
     * @returns {Boolean}
     */

  }, {
    key: "_isHiddenColumn",
    value: function _isHiddenColumn(column) {
      return this.hot.hasHook('hiddenColumn') && this.hot.runHooks('hiddenColumn', column);
    }
  }]);

  return DataProvider;
}();

export default DataProvider;