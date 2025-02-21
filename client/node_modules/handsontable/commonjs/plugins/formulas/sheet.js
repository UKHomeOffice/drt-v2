"use strict";

require("core-js/modules/es.symbol");

require("core-js/modules/es.symbol.description");

require("core-js/modules/es.symbol.iterator");

require("core-js/modules/es.array.from");

require("core-js/modules/es.array.iterator");

require("core-js/modules/es.object.to-string");

require("core-js/modules/es.regexp.to-string");

require("core-js/modules/es.string.iterator");

require("core-js/modules/web.dom-collections.iterator");

exports.__esModule = true;
exports.default = void 0;

var _hotFormulaParser = require("hot-formula-parser");

var _array = require("../../helpers/array");

var _localHooks = _interopRequireDefault(require("../../mixins/localHooks"));

var _recordTranslator = require("../../utils/recordTranslator");

var _object = require("../../helpers/object");

var _value = _interopRequireDefault(require("./cell/value"));

var _reference = _interopRequireDefault(require("./cell/reference"));

var _utils = require("./utils");

var _matrix = _interopRequireDefault(require("./matrix"));

var _alterManager = _interopRequireDefault(require("./alterManager"));

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _toConsumableArray(arr) { return _arrayWithoutHoles(arr) || _iterableToArray(arr) || _nonIterableSpread(); }

function _nonIterableSpread() { throw new TypeError("Invalid attempt to spread non-iterable instance"); }

function _iterableToArray(iter) { if (Symbol.iterator in Object(iter) || Object.prototype.toString.call(iter) === "[object Arguments]") return Array.from(iter); }

function _arrayWithoutHoles(arr) { if (Array.isArray(arr)) { for (var i = 0, arr2 = new Array(arr.length); i < arr.length; i++) { arr2[i] = arr[i]; } return arr2; } }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

var STATE_UP_TO_DATE = 1;
var STATE_NEED_REBUILD = 2;
var STATE_NEED_FULL_REBUILD = 3;
/**
 * Sheet component responsible for whole spreadsheet calculations.
 *
 * @class Sheet
 * @util
 */

var Sheet =
/*#__PURE__*/
function () {
  function Sheet(hot, dataProvider) {
    var _this = this;

    _classCallCheck(this, Sheet);

    /**
     * Handsontable instance.
     *
     * @type {Core}
     */
    this.hot = hot;
    /**
     * Record translator for translating visual records into psychical and vice versa.
     *
     * @type {RecordTranslator}
     */

    this.t = (0, _recordTranslator.getTranslator)(this.hot);
    /**
     * Data provider for sheet calculations.
     *
     * @type {DataProvider}
     */

    this.dataProvider = dataProvider;
    /**
     * Instance of {@link https://github.com/handsontable/formula-parser}.
     *
     * @type {Parser}
     */

    this.parser = new _hotFormulaParser.Parser();
    /**
     * Instance of {@link Matrix}.
     *
     * @type {Matrix}
     */

    this.matrix = new _matrix.default(this.t);
    /**
     * Instance of {@link AlterManager}.
     *
     * @type {AlterManager}
     */

    this.alterManager = new _alterManager.default(this);
    /**
     * Cell object which indicates which cell is currently processing.
     *
     * @private
     * @type {null}
     */

    this._processingCell = null;
    /**
     * State of the sheet.
     *
     * @type {Number}
     * @private
     */

    this._state = STATE_NEED_FULL_REBUILD;
    this.parser.on('callCellValue', function () {
      return _this._onCallCellValue.apply(_this, arguments);
    });
    this.parser.on('callRangeValue', function () {
      return _this._onCallRangeValue.apply(_this, arguments);
    });
    this.alterManager.addLocalHook('afterAlter', function () {
      return _this._onAfterAlter.apply(_this, arguments);
    });
  }
  /**
   * Recalculate sheet.
   */


  _createClass(Sheet, [{
    key: "recalculate",
    value: function recalculate() {
      switch (this._state) {
        case STATE_NEED_FULL_REBUILD:
          this.recalculateFull();
          break;

        case STATE_NEED_REBUILD:
          this.recalculateOptimized();
          break;

        default:
          break;
      }
    }
    /**
     * Recalculate sheet using optimized methods (fast recalculation).
     */

  }, {
    key: "recalculateOptimized",
    value: function recalculateOptimized() {
      var _this2 = this;

      var cells = this.matrix.getOutOfDateCells();
      (0, _array.arrayEach)(cells, function (cellValue) {
        var value = _this2.dataProvider.getSourceDataAtCell(cellValue.row, cellValue.column);

        if ((0, _utils.isFormulaExpression)(value)) {
          _this2.parseExpression(cellValue, value.substr(1));
        }
      });
      this._state = STATE_UP_TO_DATE;
      this.runLocalHooks('afterRecalculate', cells, 'optimized');
    }
    /**
     * Recalculate whole table by building dependencies from scratch (slow recalculation).
     */

  }, {
    key: "recalculateFull",
    value: function recalculateFull() {
      var _this3 = this;

      var cells = this.dataProvider.getSourceDataByRange();
      this.matrix.reset();
      (0, _array.arrayEach)(cells, function (rowData, row) {
        (0, _array.arrayEach)(rowData, function (value, column) {
          if ((0, _utils.isFormulaExpression)(value)) {
            _this3.parseExpression(new _value.default(row, column), value.substr(1));
          }
        });
      });
      this._state = STATE_UP_TO_DATE;
      this.runLocalHooks('afterRecalculate', cells, 'full');
    }
    /**
     * Set predefined variable name which can be visible while parsing formula expression.
     *
     * @param {String} name Variable name.
     * @param {*} value Variable value.
     */

  }, {
    key: "setVariable",
    value: function setVariable(name, value) {
      this.parser.setVariable(name, value);
    }
    /**
     * Get variable name.
     *
     * @param {String} name Variable name.
     * @returns {*}
     */

  }, {
    key: "getVariable",
    value: function getVariable(name) {
      return this.parser.getVariable(name);
    }
    /**
     * Apply changes to the sheet.
     *
     * @param {Number} row Physical row index.
     * @param {Number} column Physical column index.
     * @param {*} newValue Current cell value.
     */

  }, {
    key: "applyChanges",
    value: function applyChanges(row, column, newValue) {
      // Remove formula description for old expression
      // TODO: Move this to recalculate()
      this.matrix.remove({
        row: row,
        column: column
      }); // TODO: Move this to recalculate()

      if ((0, _utils.isFormulaExpression)(newValue)) {
        // ...and create new for new changed formula expression
        this.parseExpression(new _value.default(row, column), newValue.substr(1));
      }

      var deps = this.getCellDependencies.apply(this, _toConsumableArray(this.t.toVisual(row, column)));
      (0, _array.arrayEach)(deps, function (cellValue) {
        cellValue.setState(_value.default.STATE_OUT_OFF_DATE);
      });
      this._state = STATE_NEED_REBUILD;
    }
    /**
     * Parse and evaluate formula for provided cell.
     *
     * @param {CellValue|Object} cellValue Cell value object.
     * @param {String} formula Value to evaluate.
     */

  }, {
    key: "parseExpression",
    value: function parseExpression(cellValue, formula) {
      cellValue.setState(_value.default.STATE_COMPUTING);
      this._processingCell = cellValue;

      var _this$parser$parse = this.parser.parse((0, _utils.toUpperCaseFormula)(formula)),
          error = _this$parser$parse.error,
          result = _this$parser$parse.result;

      if ((0, _utils.isFormulaExpression)(result)) {
        this.parseExpression(cellValue, result.substr(1));
      } else {
        cellValue.setValue(result);
        cellValue.setError(error);
        cellValue.setState(_value.default.STATE_UP_TO_DATE);
      }

      this.matrix.add(cellValue);
      this._processingCell = null;
    }
    /**
     * Get cell value object at specified physical coordinates.
     *
     * @param {Number} row Physical row index.
     * @param {Number} column Physical column index.
     * @returns {CellValue|undefined}
     */

  }, {
    key: "getCellAt",
    value: function getCellAt(row, column) {
      return this.matrix.getCellAt(row, column);
    }
    /**
     * Get cell dependencies at specified physical coordinates.
     *
     * @param {Number} row Physical row index.
     * @param {Number} column Physical column index.
     * @returns {Array}
     */

  }, {
    key: "getCellDependencies",
    value: function getCellDependencies(row, column) {
      return this.matrix.getDependencies({
        row: row,
        column: column
      });
    }
    /**
     * Listener for parser cell value.
     *
     * @private
     * @param {Object} cellCoords Cell coordinates.
     * @param {Function} done Function to call with valid cell value.
     */

  }, {
    key: "_onCallCellValue",
    value: function _onCallCellValue(_ref, done) {
      var row = _ref.row,
          column = _ref.column;
      var cell = new _reference.default(row, column);

      if (!this.dataProvider.isInDataRange(cell.row, cell.column)) {
        throw Error(_hotFormulaParser.ERROR_REF);
      }

      this.matrix.registerCellRef(cell);

      this._processingCell.addPrecedent(cell);

      var cellValue = this.dataProvider.getRawDataAtCell(row.index, column.index);

      if ((0, _hotFormulaParser.error)(cellValue)) {
        var computedCell = this.matrix.getCellAt(row.index, column.index);

        if (computedCell && computedCell.hasError()) {
          throw Error(cellValue);
        }
      }

      if ((0, _utils.isFormulaExpression)(cellValue)) {
        var _this$parser$parse2 = this.parser.parse(cellValue.substr(1)),
            error = _this$parser$parse2.error,
            result = _this$parser$parse2.result;

        if (error) {
          throw Error(error);
        }

        done(result);
      } else {
        done(cellValue);
      }
    }
    /**
     * Listener for parser cells (range) value.
     *
     * @private
     * @param {Object} startCell Cell coordinates (top-left corner coordinate).
     * @param {Object} endCell Cell coordinates (bottom-right corner coordinate).
     * @param {Function} done Function to call with valid cells values.
     */

  }, {
    key: "_onCallRangeValue",
    value: function _onCallRangeValue(_ref2, _ref3, done) {
      var _this4 = this;

      var startRow = _ref2.row,
          startColumn = _ref2.column;
      var endRow = _ref3.row,
          endColumn = _ref3.column;
      var cellValues = this.dataProvider.getRawDataByRange(startRow.index, startColumn.index, endRow.index, endColumn.index);

      var mapRowData = function mapRowData(rowData, rowIndex) {
        return (0, _array.arrayMap)(rowData, function (cellData, columnIndex) {
          var rowCellCoord = startRow.index + rowIndex;
          var columnCellCoord = startColumn.index + columnIndex;
          var cell = new _reference.default(rowCellCoord, columnCellCoord);

          if (!_this4.dataProvider.isInDataRange(cell.row, cell.column)) {
            throw Error(_hotFormulaParser.ERROR_REF);
          }

          _this4.matrix.registerCellRef(cell);

          _this4._processingCell.addPrecedent(cell);

          var newCellData = cellData;

          if ((0, _hotFormulaParser.error)(newCellData)) {
            var computedCell = _this4.matrix.getCellAt(cell.row, cell.column);

            if (computedCell && computedCell.hasError()) {
              throw Error(newCellData);
            }
          }

          if ((0, _utils.isFormulaExpression)(newCellData)) {
            var _this4$parser$parse = _this4.parser.parse(newCellData.substr(1)),
                error = _this4$parser$parse.error,
                result = _this4$parser$parse.result;

            if (error) {
              throw Error(error);
            }

            newCellData = result;
          }

          return newCellData;
        });
      };

      var calculatedCellValues = (0, _array.arrayMap)(cellValues, function (rowData, rowIndex) {
        return mapRowData(rowData, rowIndex);
      });
      done(calculatedCellValues);
    }
    /**
     * On after alter sheet listener.
     *
     * @private
     */

  }, {
    key: "_onAfterAlter",
    value: function _onAfterAlter() {
      this.recalculateOptimized();
    }
    /**
     * Destroy class.
     */

  }, {
    key: "destroy",
    value: function destroy() {
      this.hot = null;
      this.t = null;
      this.dataProvider.destroy();
      this.dataProvider = null;
      this.alterManager.destroy();
      this.alterManager = null;
      this.parser = null;
      this.matrix.reset();
      this.matrix = null;
    }
  }]);

  return Sheet;
}();

(0, _object.mixin)(Sheet, _localHooks.default);
var _default = Sheet;
exports.default = _default;