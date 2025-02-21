"use strict";

require("core-js/modules/es.symbol");

require("core-js/modules/es.symbol.description");

require("core-js/modules/es.symbol.iterator");

require("core-js/modules/es.array.concat");

require("core-js/modules/es.array.index-of");

require("core-js/modules/es.array.iterator");

require("core-js/modules/es.object.get-own-property-descriptor");

require("core-js/modules/es.object.get-prototype-of");

require("core-js/modules/es.object.set-prototype-of");

require("core-js/modules/es.object.to-string");

require("core-js/modules/es.reflect.get");

require("core-js/modules/es.string.iterator");

require("core-js/modules/es.string.replace");

require("core-js/modules/es.string.split");

require("core-js/modules/web.dom-collections.iterator");

exports.__esModule = true;
exports.default = void 0;

var _base = _interopRequireDefault(require("../_base"));

var _object = require("../../helpers/object");

var _plugins = require("../../plugins");

var _endpoints = _interopRequireDefault(require("./endpoints"));

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _typeof(obj) { if (typeof Symbol === "function" && typeof Symbol.iterator === "symbol") { _typeof = function _typeof(obj) { return typeof obj; }; } else { _typeof = function _typeof(obj) { return obj && typeof Symbol === "function" && obj.constructor === Symbol && obj !== Symbol.prototype ? "symbol" : typeof obj; }; } return _typeof(obj); }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

function _possibleConstructorReturn(self, call) { if (call && (_typeof(call) === "object" || typeof call === "function")) { return call; } return _assertThisInitialized(self); }

function _assertThisInitialized(self) { if (self === void 0) { throw new ReferenceError("this hasn't been initialised - super() hasn't been called"); } return self; }

function _get(target, property, receiver) { if (typeof Reflect !== "undefined" && Reflect.get) { _get = Reflect.get; } else { _get = function _get(target, property, receiver) { var base = _superPropBase(target, property); if (!base) return; var desc = Object.getOwnPropertyDescriptor(base, property); if (desc.get) { return desc.get.call(receiver); } return desc.value; }; } return _get(target, property, receiver || target); }

function _superPropBase(object, property) { while (!Object.prototype.hasOwnProperty.call(object, property)) { object = _getPrototypeOf(object); if (object === null) break; } return object; }

function _getPrototypeOf(o) { _getPrototypeOf = Object.setPrototypeOf ? Object.getPrototypeOf : function _getPrototypeOf(o) { return o.__proto__ || Object.getPrototypeOf(o); }; return _getPrototypeOf(o); }

function _inherits(subClass, superClass) { if (typeof superClass !== "function" && superClass !== null) { throw new TypeError("Super expression must either be null or a function"); } subClass.prototype = Object.create(superClass && superClass.prototype, { constructor: { value: subClass, writable: true, configurable: true } }); if (superClass) _setPrototypeOf(subClass, superClass); }

function _setPrototypeOf(o, p) { _setPrototypeOf = Object.setPrototypeOf || function _setPrototypeOf(o, p) { o.__proto__ = p; return o; }; return _setPrototypeOf(o, p); }

/**
 * @plugin ColumnSummary
 *
 * @description
 * Allows making pre-defined calculations on the cell values and display the results within Handsontable.
 * [See the demo for more information](https://docs.handsontable.com/pro/demo-summary-calculations.html).
 *s
 * @example
 * const container = document.getElementById('example');
 * const hot = new Handsontable(container, {
 *   data: getData(),
 *   colHeaders: true,
 *   rowHeaders: true,
 *   columnSummary: [
 *     {
 *       destinationRow: 4,
 *       destinationColumn: 1,
 *       type: 'min'
 *     },
 *     {
 *       destinationRow: 0,
 *       destinationColumn: 3,
 *       reversedRowCoords: true,
 *       type: 'max'
 *     },
 *     {
 *       destinationRow: 4,
 *       destinationColumn: 5,
 *       type: 'sum',
 *       forceNumeric: true
 *     }
 *   ]
 * });
 */
var ColumnSummary =
/*#__PURE__*/
function (_BasePlugin) {
  _inherits(ColumnSummary, _BasePlugin);

  function ColumnSummary(hotInstance) {
    var _this;

    _classCallCheck(this, ColumnSummary);

    _this = _possibleConstructorReturn(this, _getPrototypeOf(ColumnSummary).call(this, hotInstance));
    /**
     * The Endpoints class instance. Used to make all endpoint-related operations.
     *
     * @private
     * @type {null|Endpoints}
     */

    _this.endpoints = null;
    return _this;
  }
  /**
   * Checks if the plugin is enabled in the handsontable settings. This method is executed in {@link Hooks#beforeInit}
   * hook and if it returns `true` than the {@link ColumnSummary#enablePlugin} method is called.
   *
   * @returns {Boolean}
   */


  _createClass(ColumnSummary, [{
    key: "isEnabled",
    value: function isEnabled() {
      return !!this.hot.getSettings().columnSummary;
    }
    /**
     * Enables the plugin functionality for this Handsontable instance.
     */

  }, {
    key: "enablePlugin",
    value: function enablePlugin() {
      var _this2 = this;

      if (this.enabled) {
        return;
      }

      this.settings = this.hot.getSettings().columnSummary;
      this.endpoints = new _endpoints.default(this, this.settings);
      this.addHook('afterInit', function () {
        return _this2.onAfterInit.apply(_this2, arguments);
      });
      this.addHook('afterChange', function () {
        return _this2.onAfterChange.apply(_this2, arguments);
      });
      this.addHook('beforeCreateRow', function (index, amount, source) {
        return _this2.endpoints.resetSetupBeforeStructureAlteration('insert_row', index, amount, null, source);
      });
      this.addHook('beforeCreateCol', function (index, amount, source) {
        return _this2.endpoints.resetSetupBeforeStructureAlteration('insert_col', index, amount, null, source);
      });
      this.addHook('beforeRemoveRow', function () {
        var _this2$endpoints;

        for (var _len = arguments.length, args = new Array(_len), _key = 0; _key < _len; _key++) {
          args[_key] = arguments[_key];
        }

        return (_this2$endpoints = _this2.endpoints).resetSetupBeforeStructureAlteration.apply(_this2$endpoints, ['remove_row'].concat(args));
      });
      this.addHook('beforeRemoveCol', function () {
        var _this2$endpoints2;

        for (var _len2 = arguments.length, args = new Array(_len2), _key2 = 0; _key2 < _len2; _key2++) {
          args[_key2] = arguments[_key2];
        }

        return (_this2$endpoints2 = _this2.endpoints).resetSetupBeforeStructureAlteration.apply(_this2$endpoints2, ['remove_col'].concat(args));
      });
      this.addHook('beforeRowMove', function () {
        return _this2.onBeforeRowMove.apply(_this2, arguments);
      });
      this.addHook('afterCreateRow', function (index, amount, source) {
        return _this2.endpoints.resetSetupAfterStructureAlteration('insert_row', index, amount, null, source);
      });
      this.addHook('afterCreateCol', function (index, amount, source) {
        return _this2.endpoints.resetSetupAfterStructureAlteration('insert_col', index, amount, null, source);
      });
      this.addHook('afterRemoveRow', function () {
        var _this2$endpoints3;

        for (var _len3 = arguments.length, args = new Array(_len3), _key3 = 0; _key3 < _len3; _key3++) {
          args[_key3] = arguments[_key3];
        }

        return (_this2$endpoints3 = _this2.endpoints).resetSetupAfterStructureAlteration.apply(_this2$endpoints3, ['remove_row'].concat(args));
      });
      this.addHook('afterRemoveCol', function () {
        var _this2$endpoints4;

        for (var _len4 = arguments.length, args = new Array(_len4), _key4 = 0; _key4 < _len4; _key4++) {
          args[_key4] = arguments[_key4];
        }

        return (_this2$endpoints4 = _this2.endpoints).resetSetupAfterStructureAlteration.apply(_this2$endpoints4, ['remove_col'].concat(args));
      });
      this.addHook('afterRowMove', function () {
        return _this2.onAfterRowMove.apply(_this2, arguments);
      });

      _get(_getPrototypeOf(ColumnSummary.prototype), "enablePlugin", this).call(this);
    }
    /**
     * Disables the plugin functionality for this Handsontable instance.
     */

  }, {
    key: "disablePlugin",
    value: function disablePlugin() {
      this.endpoints = null;
      this.settings = null;
      this.currentEndpoint = null;
    }
    /**
     * Calculates math for a single endpoint.
     *
     * @private
     * @param {Object} endpoint Contains information about the endpoint.
     */

  }, {
    key: "calculate",
    value: function calculate(endpoint) {
      switch (endpoint.type.toLowerCase()) {
        case 'sum':
          endpoint.result = this.calculateSum(endpoint);
          break;

        case 'min':
          endpoint.result = this.calculateMinMax(endpoint, endpoint.type);
          break;

        case 'max':
          endpoint.result = this.calculateMinMax(endpoint, endpoint.type);
          break;

        case 'count':
          endpoint.result = this.countEntries(endpoint);
          break;

        case 'average':
          endpoint.result = this.calculateAverage(endpoint);
          break;

        case 'custom':
          endpoint.result = endpoint.customFunction.call(this, endpoint);
          break;

        default:
          break;
      }
    }
    /**
     * Calculates sum of the values contained in ranges provided in the plugin config.
     *
     * @private
     * @param {Object} endpoint Contains the endpoint information.
     * @returns {Number} Sum for the selected range
     */

  }, {
    key: "calculateSum",
    value: function calculateSum(endpoint) {
      var _this3 = this;

      var sum = 0;
      (0, _object.objectEach)(endpoint.ranges, function (range) {
        sum += _this3.getPartialSum(range, endpoint.sourceColumn);
      });
      return sum;
    }
    /**
     * Returns partial sum of values from a single row range
     *
     * @private
     * @param {Array} rowRange Range for the sum.
     * @param {Number} col Column index.
     * @returns {Number} The partial sum.
     */

  }, {
    key: "getPartialSum",
    value: function getPartialSum(rowRange, col) {
      var sum = 0;
      var i = rowRange[1] || rowRange[0];
      var cellValue = null;
      var biggestDecimalPlacesCount = 0;

      do {
        cellValue = this.getCellValue(i, col) || 0;
        var decimalPlaces = ("".concat(cellValue).split('.')[1] || []).length || 1;

        if (decimalPlaces > biggestDecimalPlacesCount) {
          biggestDecimalPlacesCount = decimalPlaces;
        }

        sum += cellValue || 0;
        i -= 1;
      } while (i >= rowRange[0]); // Workaround for e.g. 802.2 + 1.1 = 803.3000000000001


      return Math.round(sum * Math.pow(10, biggestDecimalPlacesCount)) / Math.pow(10, biggestDecimalPlacesCount);
    }
    /**
     * Calculates the minimal value for the selected ranges
     *
     * @private
     * @param {Object} endpoint Contains the endpoint information.
     * @param {String} type `'min'` or `'max'`.
     * @returns {Number} Min or Max value.
     */

  }, {
    key: "calculateMinMax",
    value: function calculateMinMax(endpoint, type) {
      var _this4 = this;

      var result = null;
      (0, _object.objectEach)(endpoint.ranges, function (range) {
        var partialResult = _this4.getPartialMinMax(range, endpoint.sourceColumn, type);

        if (result === null && partialResult !== null) {
          result = partialResult;
        }

        if (partialResult !== null) {
          switch (type) {
            case 'min':
              result = Math.min(result, partialResult);
              break;

            case 'max':
              result = Math.max(result, partialResult);
              break;

            default:
              break;
          }
        }
      });
      return result === null ? 'Not enough data' : result;
    }
    /**
     * Returns a local minimum of the provided sub-range
     *
     * @private
     * @param {Array} rowRange Range for the calculation.
     * @param {Number} col Column index.
     * @param {String} type `'min'` or `'max'`
     * @returns {Number} Min or max value.
     */

  }, {
    key: "getPartialMinMax",
    value: function getPartialMinMax(rowRange, col, type) {
      var result = null;
      var i = rowRange[1] || rowRange[0];
      var cellValue;

      do {
        cellValue = this.getCellValue(i, col) || null;

        if (result === null) {
          result = cellValue;
        } else if (cellValue !== null) {
          switch (type) {
            case 'min':
              result = Math.min(result, cellValue);
              break;

            case 'max':
              result = Math.max(result, cellValue);
              break;

            default:
              break;
          }
        }

        i -= 1;
      } while (i >= rowRange[0]);

      return result;
    }
    /**
     * Counts empty cells in the provided row range.
     *
     * @private
     * @param {Array} rowRange Row range for the calculation.
     * @param {Number} col Column index.
     * @returns {Number} Empty cells count.
     */

  }, {
    key: "countEmpty",
    value: function countEmpty(rowRange, col) {
      var cellValue;
      var counter = 0;
      var i = rowRange[1] || rowRange[0];

      do {
        cellValue = this.getCellValue(i, col);

        if (!cellValue) {
          counter += 1;
        }

        i -= 1;
      } while (i >= rowRange[0]);

      return counter;
    }
    /**
     * Counts non-empty cells in the provided row range.
     *
     * @private
     * @param {Object} endpoint Contains the endpoint information.
     * @returns {Number} Entry count.
     */

  }, {
    key: "countEntries",
    value: function countEntries(endpoint) {
      var _this5 = this;

      var result = 0;
      var ranges = endpoint.ranges;
      (0, _object.objectEach)(ranges, function (range) {
        var partial = range[1] === void 0 ? 1 : range[1] - range[0] + 1;

        var emptyCount = _this5.countEmpty(range, endpoint.sourceColumn);

        result += partial;
        result -= emptyCount;
      });
      return result;
    }
    /**
     * Calculates the average value from the cells in the range.
     *
     * @private
     * @param {Object} endpoint Contains the endpoint information.
     * @returns {Number} Avarage value.
     */

  }, {
    key: "calculateAverage",
    value: function calculateAverage(endpoint) {
      var sum = this.calculateSum(endpoint);
      var entriesCount = this.countEntries(endpoint);
      return sum / entriesCount;
    }
    /**
     * Returns a cell value, taking into consideration a basic validation.
     *
     * @private
     * @param {Number} row Row index.
     * @param {Number} col Column index.
     * @returns {String} The cell value.
     */

  }, {
    key: "getCellValue",
    value: function getCellValue(row, col) {
      var visualRowIndex = this.endpoints.getVisualRowIndex(row);
      var visualColumnIndex = this.endpoints.getVisualColumnIndex(col);
      var cellValue = this.hot.getSourceDataAtCell(row, col);
      var cellClassName = this.hot.getCellMeta(visualRowIndex, visualColumnIndex).className || '';

      if (cellClassName.indexOf('columnSummaryResult') > -1) {
        return null;
      }

      if (this.endpoints.currentEndpoint.forceNumeric) {
        if (typeof cellValue === 'string') {
          cellValue = cellValue.replace(/,/, '.');
        }

        cellValue = parseFloat(cellValue);
      }

      if (isNaN(cellValue)) {
        if (!this.endpoints.currentEndpoint.suppressDataTypeErrors) {
          throw new Error("ColumnSummary plugin: cell at (".concat(row, ", ").concat(col, ") is not in a numeric format. Cannot do the calculation."));
        }
      }

      return cellValue;
    }
    /**
     * `afterInit` hook callback.
     *
     * @private
     */

  }, {
    key: "onAfterInit",
    value: function onAfterInit() {
      this.endpoints.endpoints = this.endpoints.parseSettings();
      this.endpoints.refreshAllEndpoints(true);
    }
    /**
     * `afterChange` hook callback.
     *
     * @private
     * @param {Array} changes
     * @param {String} source
     */

  }, {
    key: "onAfterChange",
    value: function onAfterChange(changes, source) {
      if (changes && source !== 'ColumnSummary.reset' && source !== 'ColumnSummary.set' && source !== 'loadData') {
        this.endpoints.refreshChangedEndpoints(changes);
      }
    }
    /**
     * `beforeRowMove` hook callback.
     *
     * @private
     * @param {Array} rows Array of logical rows to be moved.
     */

  }, {
    key: "onBeforeRowMove",
    value: function onBeforeRowMove(rows) {
      this.endpoints.resetSetupBeforeStructureAlteration('move_row', rows[0], rows.length, rows, this.pluginName);
    }
    /**
     * `afterRowMove` hook callback.
     *
     * @private
     * @param {Array} rows Array of logical rows that were moved.
     * @param {Number} target Index of the destination row.
     */

  }, {
    key: "onAfterRowMove",
    value: function onAfterRowMove(rows, target) {
      this.endpoints.resetSetupAfterStructureAlteration('move_row', target, rows.length, rows, this.pluginName);
    }
  }]);

  return ColumnSummary;
}(_base.default);

(0, _plugins.registerPlugin)('columnSummary', ColumnSummary);
var _default = ColumnSummary;
exports.default = _default;