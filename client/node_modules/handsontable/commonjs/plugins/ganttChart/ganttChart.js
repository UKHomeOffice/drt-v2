"use strict";

require("core-js/modules/es.symbol");

require("core-js/modules/es.symbol.description");

require("core-js/modules/es.symbol.iterator");

require("core-js/modules/es.array.concat");

require("core-js/modules/es.array.iterator");

require("core-js/modules/es.array.slice");

require("core-js/modules/es.object.get-own-property-descriptor");

require("core-js/modules/es.object.get-prototype-of");

require("core-js/modules/es.object.set-prototype-of");

require("core-js/modules/es.object.to-string");

require("core-js/modules/es.reflect.get");

require("core-js/modules/es.string.iterator");

require("core-js/modules/es.string.replace");

require("core-js/modules/web.dom-collections.iterator");

exports.__esModule = true;
exports.default = void 0;

var _base = _interopRequireDefault(require("../_base"));

var _element = require("../../helpers/dom/element");

var _object = require("../../helpers/object");

var _console = require("../../helpers/console");

var _data = require("../../helpers/data");

var _plugins = require("../../plugins");

var _utils = require("./utils");

var _dateCalculator = _interopRequireDefault(require("./dateCalculator"));

var _ganttChartDataFeed = _interopRequireDefault(require("./ganttChartDataFeed"));

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
 * @plugin GanttChart
 * @experimental
 * @dependencies CollapsibleColumns
 *
 * @description
 * GanttChart plugin enables a possibility to create a Gantt chart using a Handsontable instance.
 * In this case, the whole table becomes read-only.
 *
 * @example
 * ```js
 * ganttChart: {
 *     dataSource: data,
 *     firstWeekDay: 'monday', // Sets the first day of the week to either 'monday' or 'sunday'.
 *     startYear: 2015 // Sets the displayed year to the provided value.
 *     weekHeaderGenerator: function(start, end) { return start + ' - ' + end; } // sets the label on the week column headers (optional). The `start` and `end` arguments are numbers representing the beginning and end day of the week.
 *     allowSplitWeeks: true, // If set to `true` (default), will allow splitting week columns between months. If not, plugin will generate "mixed" months, like "Jan/Feb".
 *     hideDaysBeforeFullWeeks: false, // If set to `true`, the plugin won't render the incomplete weeks before the "full" weeks inside months.
 *     hideDaysAfterFullWeeks: false, // If set to `true`, the plugin won't render the incomplete weeks after the "full" weeks inside months.
 *   }
 *
 * // Where data can be either an data object or an object containing information about another Handsontable instance, which
 * // would feed the chart-enabled instance with data.
 * // For example:
 *
 * // Handsontable-binding information
 * var data = {
 *   instance: source, // reference to another Handsontable instance
 *   startDateColumn: 4, // index of a column, which contains information about start dates of data ranges
 *   endDateColumn: 5, // index of a column, which contains information about end dates of data ranges
 *   additionalData: { // information about additional data passed to the chart, in this example example:
 *     label: 0, // labels are stored in the first column
 *     quantity: 1 // quantity information is stored in the second column
 *   },
 *   asyncUpdates: true // if set to true, the updates from the source instance with be asynchronous. Defaults to false.
 * }
 *
 * // Data object
 * var data = [
 *   {
 *     additionalData: {label: 'Example label.', quantity: 'Four packs.'},
 *     startDate: '1/5/2015',
 *     endDate: '1/20/2015'
 *   },
 *   {
 *     additionalData: {label: 'Another label.', quantity: 'One pack.'},
 *     startDate: '1/11/2015',
 *     endDate: '1/29/2015'
 *   }
 * ];
 * ```
 */
var GanttChart =
/*#__PURE__*/
function (_BasePlugin) {
  _inherits(GanttChart, _BasePlugin);

  function GanttChart(hotInstance) {
    var _this;

    _classCallCheck(this, GanttChart);

    _this = _possibleConstructorReturn(this, _getPrototypeOf(GanttChart).call(this, hotInstance));
    /**
     * Cached plugin settings.
     *
     * @private
     * @type {Object}
     */

    _this.settings = {};
    /**
     * Date Calculator object.
     *
     * @private
     * @type {DateCalculator}
     */

    _this.dateCalculator = null;
    /**
     * Currently loaded year.
     *
     * @private
     * @type {Number}
     */

    _this.currentYear = null;
    /**
     * List of months and their corresponding day counts.
     *
     * @private
     * @type {Array}
     */

    _this.monthList = [];
    /**
     * Array of data for the month headers.
     *
     * @private
     * @type {Array}
     */

    _this.monthHeadersArray = [];
    /**
     * Array of data for the week headers.
     *
     * @private
     * @type {Array}
     */

    _this.weekHeadersArray = [];
    /**
     * Object containing the currently created range bars, along with their corresponding parameters.
     *
     * @private
     * @type {Object}
     */

    _this.rangeBars = {};
    /**
     * Object containing the currently created ranges with coordinates to their range bars.
     * It's structure is categorized by years, so to get range bar information for a year, one must use `this.rangeList[year]`.
     *
     * @private
     * @type {Object}
     */

    _this.rangeList = {};
    /**
     * Reference to the Nested Headers plugin.
     *
     * @private
     * @type {NestedHeaders}
     */

    _this.nestedHeadersPlugin = null;
    /**
     * Object containing properties of the source Handsontable instance (the data source).
     *
     * @private
     * @type {Object}
     */

    _this.hotSource = null;
    /**
     * Number of week 'blocks' in the nested headers.
     *
     * @private
     * @type {Number}
     */

    _this.overallWeekSectionCount = null;
    /**
     * Initial instance settings - used to rollback the gantt-specific settings during the disabling of the plugin.
     *
     * @private
     * @type {Object}
     */

    _this.initialSettings = null;
    /**
     * Data feed controller for this plugin.
     *
     * @private
     * @type {GanttChartDataFeed}
     */

    _this.dataFeed = null;
    /**
     * Color information set after applying colors to the chart.
     *
     * @private
     * @type {Object}
     */

    _this.colorData = {};
    /**
     * Metadata of the range bars, used to re-apply meta after updating HOT settings.
     *
     * @private
     * @type {Object}
     */

    _this.rangeBarMeta = Object.create(null);
    return _this;
  }
  /**
   * Check if the dependencies are met, if not, throws a warning.
   */


  _createClass(GanttChart, [{
    key: "checkDependencies",
    value: function checkDependencies() {
      if (!this.hot.getSettings().colHeaders) {
        (0, _console.warn)('You need to enable the colHeaders property in your Gantt Chart Handsontable in order for it to work properly.');
      }
    }
    /**
     * Checks if the plugin is enabled in the handsontable settings. This method is executed in {@link Hooks#beforeInit}
     * hook and if it returns `true` than the {@link GanttChart#enablePlugin} method is called.
     *
     * @returns {Boolean}
     */

  }, {
    key: "isEnabled",
    value: function isEnabled() {
      return !!this.hot.getSettings().ganttChart;
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

      this.checkDependencies();
      this.parseSettings();
      this.currentYear = this.settings.startYear || new Date().getFullYear();
      this.dateCalculator = new _dateCalculator.default({
        year: this.currentYear,
        allowSplitWeeks: this.settings.allowSplitWeeks,
        hideDaysBeforeFullWeeks: this.settings.hideDaysBeforeFullWeeks,
        hideDaysAfterFullWeeks: this.settings.hideDaysAfterFullWeeks
      });
      this.dateCalculator.setFirstWeekDay(this.settings.firstWeekDay);
      this.monthList = this.dateCalculator.getMonthList();
      this.monthHeadersArray = this.generateMonthHeaders();
      this.weekHeadersArray = this.generateWeekHeaders();
      this.overallWeekSectionCount = this.dateCalculator.countWeekSections();
      this.assignGanttSettings();

      if (this.nestedHeadersPlugin) {
        this.applyDataSource();

        if (this.colorData) {
          this.setRangeBarColors(this.colorData);
        }
      }

      this.addHook('afterInit', function () {
        return _this2.onAfterInit();
      });
      (0, _element.addClass)(this.hot.rootElement, 'ganttChart');

      _get(_getPrototypeOf(GanttChart.prototype), "enablePlugin", this).call(this);
    }
    /**
     * Disables the plugin functionality for this Handsontable instance.
     */

  }, {
    key: "disablePlugin",
    value: function disablePlugin() {
      if (this.internalUpdateSettings) {
        return;
      }

      if (this.dataFeed && this.dataFeed.hotSource) {
        this.dataFeed.removeSourceHotHooks(this.dataFeed.hotSource);
      }

      this.settings = {};
      this.dataFeed = {};
      this.currentYear = null;
      this.monthList = [];
      this.rangeBars = {};
      this.rangeList = {};
      this.rangeBarMeta = {};
      this.hotSource = null;
      this.deassignGanttSettings();
      (0, _element.removeClass)(this.hot.rootElement, 'ganttChart');

      _get(_getPrototypeOf(GanttChart.prototype), "disablePlugin", this).call(this);
    }
    /**
     * Updates the plugin state. This method is executed when {@link Core#updateSettings} is invoked.
     */

  }, {
    key: "updatePlugin",
    value: function updatePlugin() {
      this.disablePlugin();
      this.enablePlugin();

      _get(_getPrototypeOf(GanttChart.prototype), "updatePlugin", this).call(this);
    }
    /**
     * Parses the plugin settings.
     *
     * @private
     */

  }, {
    key: "parseSettings",
    value: function parseSettings() {
      this.settings = this.hot.getSettings().ganttChart;

      if (typeof this.settings === 'boolean') {
        this.settings = {};
      }

      if (!this.settings.firstWeekDay) {
        this.settings.firstWeekDay = 'monday';
      }

      if (this.settings.allowSplitWeeks === void 0) {
        this.settings.allowSplitWeeks = true;
      }

      if (typeof this.settings.weekHeaderGenerator !== 'function') {
        this.settings.weekHeaderGenerator = null;
      }
    }
    /**
     * Applies the data source provided in the plugin settings.
     *
     * @private
     */

  }, {
    key: "applyDataSource",
    value: function applyDataSource() {
      if (this.settings.dataSource) {
        var source = this.settings.dataSource;

        if (source.instance) {
          this.loadData(source.instance, source.startDateColumn, source.endDateColumn, source.additionalData, source.asyncUpdates);
        } else {
          this.loadData(source);
        }
      }
    }
    /**
     * Loads chart data to the Handsontable instance.
     *
     * @private
     * @param {Array|Object} data Array of objects containing the range data OR another Handsontable instance, to be used as the data feed
     * @param {Number} [startDateColumn] Index of the start date column (Needed only if the data argument is a HOT instance).
     * @param {Number} [endDateColumn] Index of the end date column (Needed only if the data argument is a HOT instance).
     * @param {Object} [additionalData] Object containing additional data labels and their corresponding column indexes (Needed only if the data argument is a HOT instance).
     *
     */

  }, {
    key: "loadData",
    value: function loadData(data, startDateColumn, endDateColumn, additionalData, asyncUpdates) {
      this.dataFeed = new _ganttChartDataFeed.default(this.hot, data, startDateColumn, endDateColumn, additionalData, asyncUpdates);
      this.hot.render();
    }
    /**
     * Clears the range bars list.
     *
     * @private
     */

  }, {
    key: "clearRangeBars",
    value: function clearRangeBars() {
      this.rangeBars = {};
    }
    /**
     * Clears the range list.
     *
     * @private
     */

  }, {
    key: "clearRangeList",
    value: function clearRangeList() {
      this.rangeList = {};
    }
    /**
     * Returns a range bar coordinates by the provided row.
     *
     * @param {Number} row Range bar's row.
     * @returns {Object}
     */

  }, {
    key: "getRangeBarCoordinates",
    value: function getRangeBarCoordinates(row) {
      return this.rangeList[row];
    }
    /**
     * Generates the month header structure.
     *
     * @private
     */

  }, {
    key: "generateMonthHeaders",
    value: function generateMonthHeaders() {
      var year = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : this.currentYear;
      return this.dateCalculator.generateHeaderSet('months', this.settings.weekHeaderGenerator, year);
    }
    /**
     * Generates the week header structure.
     *
     * @private
     */

  }, {
    key: "generateWeekHeaders",
    value: function generateWeekHeaders() {
      var year = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : this.currentYear;
      return this.dateCalculator.generateHeaderSet('weeks', this.settings.weekHeaderGenerator, year);
    }
    /**
     * Assigns the settings needed for the Gantt Chart plugin into the Handsontable instance.
     *
     * @private
     */

  }, {
    key: "assignGanttSettings",
    value: function assignGanttSettings() {
      var _this3 = this;

      // TODO: commented out temporarily, to be fixed in #68, there's a problem with re-enabling the gantt settings after resetting them
      // this.initialSettings = {
      //   data: this.hot.getSettings().data,
      //   readOnly: this.hot.getSettings().readOnly,
      //   renderer: this.hot.getSettings().renderer,
      //   colWidths: this.hot.getSettings().colWidths,
      //   hiddenColumns: this.hot.getSettings().hiddenColumns,
      //   nestedHeaders: this.hot.getSettings().nestedHeaders,
      //   collapsibleColumns: this.hot.getSettings().collapsibleColumns,
      //   columnSorting: this.hot.getSettings().columnSorting,
      // };
      this.initialSettings = (0, _object.deepClone)(this.hot.getSettings());
      var additionalSettings = {
        data: (0, _data.createEmptySpreadsheetData)(1, this.overallWeekSectionCount),
        readOnly: true,
        renderer: function renderer(instance, TD, row, col, prop, value, cellProperties) {
          return _this3.uniformBackgroundRenderer(instance, TD, row, col, prop, value, cellProperties);
        },
        colWidths: 60,
        hiddenColumns: this.hot.getSettings().hiddenColumns ? this.hot.getSettings().hiddenColumns : true,
        nestedHeaders: [this.monthHeadersArray.slice(), this.weekHeadersArray.slice()],
        collapsibleColumns: this.hot.getSettings().collapsibleColumns ? this.hot.getSettings().collapsibleColumns : true,
        columnSorting: false,
        copyPaste: false
      };
      this.internalUpdateSettings = true;
      this.hot.updateSettings(additionalSettings);
      this.internalUpdateSettings = void 0;
    }
    /**
     * Deassigns the Gantt Chart plugin settings (revert to initial settings).
     *
     * @private
     */

  }, {
    key: "deassignGanttSettings",
    value: function deassignGanttSettings() {
      this.internalUpdateSettings = true;

      if (this.initialSettings) {
        this.hot.updateSettings(this.initialSettings);
      }

      this.internalUpdateSettings = void 0;
    }
    /**
     * Add rangebar meta data to the cache.
     *
     * @param {Number} row
     * @param {Number} col
     * @param {String} key
     * @param {String|Number|Object|Array} value
     */

  }, {
    key: "cacheRangeBarMeta",
    value: function cacheRangeBarMeta(row, col, key, value) {
      if (!this.rangeBarMeta[row]) {
        this.rangeBarMeta[row] = {};
      }

      if (!this.rangeBarMeta[row][col]) {
        this.rangeBarMeta[row][col] = {};
      }

      this.rangeBarMeta[row][col][key] = value;
    }
    /**
     * Applies the cached cell meta.
     *
     * @private
     */

  }, {
    key: "applyRangeBarMetaCache",
    value: function applyRangeBarMetaCache() {
      var _this4 = this;

      (0, _object.objectEach)(this.rangeBarMeta, function (rowArr, row) {
        (0, _object.objectEach)(rowArr, function (cell, col) {
          (0, _object.objectEach)(cell, function (value, key) {
            _this4.hot.setCellMeta(row, col, key, value);
          });
        });
      });
    }
    /**
     * Get the column index of the adjacent week.
     *
     * @private
     * @param {Date|String} date The date object/string.
     * @param {Boolean} following `true` if the following week is needed.
     * @param {Boolean} previous `true` if the previous week is needed.
     */

  }, {
    key: "getAdjacentWeekColumn",
    value: function getAdjacentWeekColumn(date, following, previous) {
      var convertedDate = (0, _utils.parseDate)(date);
      var delta = previous === true ? -7 : 7;
      var adjacentWeek = convertedDate.setDate(convertedDate.getDate() + delta);
      return this.dateCalculator.dateToColumn(adjacentWeek);
    }
    /**
     * Create a new range bar.
     *
     * @param {Number} row Row index.
     * @param {Date|String} startDate Start date object/string.
     * @param {Date|String} endDate End date object/string.
     * @param {Object} additionalData Additional range data.
     * @returns {Array|Boolean} Array of the bar's row and column.
     */

  }, {
    key: "addRangeBar",
    value: function addRangeBar(row, startDate, endDate, additionalData) {
      var _this5 = this;

      if (startDate !== null && endDate !== null) {
        this.prepareDaysInColumnsInfo((0, _utils.parseDate)(startDate), (0, _utils.parseDate)(endDate));
      }

      var startDateColumn = this.dateCalculator.dateToColumn(startDate);
      var endDateColumn = this.dateCalculator.dateToColumn(endDate);
      var year = (0, _utils.getDateYear)(startDate); // every range bar should not exceed the limits of one year

      var startMoved = false;
      var endMoved = false;

      if (startDateColumn === null && this.settings.hideDaysBeforeFullWeeks) {
        startDateColumn = this.getAdjacentWeekColumn(startDate, true, false);

        if (startDateColumn !== false) {
          startMoved = true;
        }
      }

      if (endDateColumn === null && this.settings.hideDaysAfterFullWeeks) {
        endDateColumn = this.getAdjacentWeekColumn(endDate, false, true);

        if (endDateColumn !== false) {
          endMoved = true;
        }
      }

      if (!this.dateCalculator.isValidRangeBarData(startDate, endDate) || startDateColumn === false || endDateColumn === false) {
        return false;
      }

      if (!this.rangeBars[year]) {
        this.rangeBars[year] = {};
      }

      if (!this.rangeBars[year][row]) {
        this.rangeBars[year][row] = {};
      }

      this.rangeBars[year][row][startDateColumn] = {
        barLength: endDateColumn - startDateColumn + 1,
        partialStart: startMoved ? false : !this.dateCalculator.isOnTheEdgeOfWeek(startDate)[0],
        partialEnd: endMoved ? false : !this.dateCalculator.isOnTheEdgeOfWeek(endDate)[1],
        additionalData: {}
      };
      (0, _object.objectEach)(additionalData, function (prop, i) {
        _this5.rangeBars[year][row][startDateColumn].additionalData[i] = prop;
      });

      if (year === this.dateCalculator.getYear()) {
        if (this.colorData[row]) {
          this.rangeBars[year][row][startDateColumn].colors = this.colorData[row];
        }

        this.rangeList[row] = [row, startDateColumn];
        this.renderRangeBar(row, startDateColumn, endDateColumn, additionalData);
      }

      return [row, startDateColumn];
    }
    /**
     * Generates the information about which date is represented in which column.
     *
     * @private
     * @param {Date} startDate Start date.
     * @param {Date} endDate End Date.
     */

  }, {
    key: "prepareDaysInColumnsInfo",
    value: function prepareDaysInColumnsInfo(startDate, endDate) {
      for (var i = startDate.getFullYear(); i <= endDate.getFullYear(); i++) {
        if (this.dateCalculator.daysInColumns[i] === void 0) {
          this.dateCalculator.calculateWeekStructure(i);
          this.dateCalculator.generateHeaderSet('weeks', null, i);
        }
      }
    }
    /**
     * Returns the range bar data of the provided row and column.
     *
     * @param {Number} row Row index.
     * @param {Number} column Column index.
     * @returns {Object|Boolean} Returns false if no bar is found.
     */

  }, {
    key: "getRangeBarData",
    value: function getRangeBarData(row, column) {
      var year = this.dateCalculator.getYear();
      var rangeBarCoords = this.getRangeBarCoordinates(row);

      if (!rangeBarCoords) {
        return false;
      }

      var rangeBarData = this.rangeBars[year][rangeBarCoords[0]][rangeBarCoords[1]];

      if (rangeBarData && row === rangeBarCoords[0] && (column === rangeBarCoords[1] || column > rangeBarCoords[1] && column < rangeBarCoords[1] + rangeBarData.barLength)) {
        return rangeBarData;
      }

      return false;
    }
    /**
     * Updates the range bar data by the provided object.
     *
     * @param {Number} row Row index.
     * @param {Number} column Column index.
     * @param {Object} data Object with the updated data.
     */

  }, {
    key: "updateRangeBarData",
    value: function updateRangeBarData(row, column, data) {
      var rangeBar = this.getRangeBarData(row, column);
      (0, _object.objectEach)(data, function (val, prop) {
        if (rangeBar[prop] !== val) {
          rangeBar[prop] = val;
        }
      });
    }
    /**
     * Adds a range bar to the table.
     *
     * @private
     * @param {Number} row Row index.
     * @param {Number} startDateColumn Start column index.
     * @param {Number} endDateColumn End column index.
     */

  }, {
    key: "renderRangeBar",
    value: function renderRangeBar(row, startDateColumn, endDateColumn) {
      var year = this.dateCalculator.getYear();
      var currentBar = this.rangeBars[year][row][startDateColumn];

      for (var i = startDateColumn; i <= endDateColumn; i++) {
        var cellMeta = this.hot.getCellMeta(row, i);
        var newClassName = "".concat(cellMeta.className || '', " rangeBar");

        if (i === startDateColumn && currentBar.partialStart || i === endDateColumn && currentBar.partialEnd) {
          newClassName += ' partial';
        }

        if (i === endDateColumn) {
          newClassName += ' last';
        }

        this.hot.setCellMeta(row, i, 'originalClassName', cellMeta.className);
        this.hot.setCellMeta(row, i, 'className', newClassName);
        this.hot.setCellMeta(row, i, 'additionalData', currentBar.additionalData); // cache cell meta, used for updateSettings, related with a cell meta bug

        this.cacheRangeBarMeta(row, i, 'originalClassName', cellMeta.className);
        this.cacheRangeBarMeta(row, i, 'className', newClassName);
        this.cacheRangeBarMeta(row, i, 'additionalData', currentBar.additionalData);
      }
    }
    /**
     * Removes a range bar of the provided start date and row.
     *
     * @param {Number} row Row index.
     * @param {Date|String} startDate Start date.
     */

  }, {
    key: "removeRangeBarByDate",
    value: function removeRangeBarByDate(row, startDate) {
      var startDateColumn = this.dateCalculator.dateToColumn(startDate);
      this.removeRangeBarByColumn(row, startDateColumn);
    }
    /**
     * Removes a range bar of the provided row and start column.
     *
     * @param {Number} row Row index.
     * @param {Number} startDateColumn Column index.
     */

  }, {
    key: "removeRangeBarByColumn",
    value: function removeRangeBarByColumn(row, startDateColumn) {
      var _this6 = this;

      var year = this.dateCalculator.getYear();
      var rangeBar = this.rangeBars[year][row][startDateColumn];

      if (!rangeBar) {
        return;
      }

      this.unrenderRangeBar(row, startDateColumn, startDateColumn + rangeBar.barLength - 1);
      this.rangeBars[year][row][startDateColumn] = null;
      (0, _object.objectEach)(this.rangeList, function (prop, i) {
        var id = parseInt(i, 10);

        if (JSON.stringify(prop) === JSON.stringify([row, startDateColumn])) {
          _this6.rangeList[id] = null;
        }
      });
    }
    /**
     * Removes all range bars from the chart-enabled Handsontable instance.
     */

  }, {
    key: "removeAllRangeBars",
    value: function removeAllRangeBars() {
      var _this7 = this;

      (0, _object.objectEach)(this.rangeBars, function (row, i) {
        (0, _object.objectEach)(row, function (bar, j) {
          _this7.removeRangeBarByColumn(i, j);
        });
      });
    }
    /**
     * Removes a range bar from the table.
     *
     * @private
     * @param {Number} row Row index.
     * @param {Number} startDateColumn Start column index.
     * @param {Number} endDateColumn End column index.
     */

  }, {
    key: "unrenderRangeBar",
    value: function unrenderRangeBar(row, startDateColumn, endDateColumn) {
      for (var i = startDateColumn; i <= endDateColumn; i++) {
        var cellMeta = this.hot.getCellMeta(row, i);
        this.hot.setCellMeta(row, i, 'className', cellMeta.originalClassName);
        this.hot.setCellMeta(row, i, 'originalClassName', void 0);
        this.cacheRangeBarMeta(row, i, 'className', cellMeta.originalClassName);
        this.cacheRangeBarMeta(row, i, 'originalClassName', void 0);
      }

      this.hot.render();
    }
    /**
     * A default renderer of the range bars.
     *
     * @private
     * @param {Object} instance HOT instance.
     * @param {HTMLElement} TD TD element.
     * @param {Number} row Row index.
     * @param {Number} col Column index.
     * @param {String|Number} prop Object data property.
     * @param {String|Number} value Value to pass to the cell.
     * @param {Object} cellProperties Current cell properties.
     */

  }, {
    key: "uniformBackgroundRenderer",
    value: function uniformBackgroundRenderer(instance, TD, row, col, prop, value, cellProperties) {
      var rangeBarInfo = this.getRangeBarData(row, col);
      var rangeBarCoords = this.getRangeBarCoordinates(row);
      TD.innerHTML = '';

      if (cellProperties.className) {
        TD.className = cellProperties.className;
      }

      var titleValue = '';
      (0, _object.objectEach)(cellProperties.additionalData, function (cellMeta, i) {
        titleValue += "".concat(i, ": ").concat(cellMeta, "\n");
      });
      titleValue = titleValue.replace(/\n$/, '');
      TD.title = titleValue;

      if (rangeBarInfo && rangeBarInfo.colors) {
        if (col === rangeBarCoords[1] && rangeBarInfo.partialStart || col === rangeBarCoords[1] + rangeBarInfo.barLength - 1 && rangeBarInfo.partialEnd) {
          TD.style.background = rangeBarInfo.colors[1];
        } else {
          TD.style.background = rangeBarInfo.colors[0];
        }
      } else {
        TD.style.background = '';
      }
    }
    /**
     * Sets range bar colors.
     *
     * @param {Object} rows Object containing row color data, see example.
     * @example
     * ```js
     *  hot.getPlugin('ganttChart').setRangeBarColors({
     *    0: ['blue', 'lightblue'] // paints the bar in the first row blue, with partial sections colored light blue
      *   2: ['#2A74D0', '#588DD0'] // paints the bar in the thrid row with #2A74D0, with partial sections colored with #588DD0
     *  });
     * ```
     */

  }, {
    key: "setRangeBarColors",
    value: function setRangeBarColors(rows) {
      var _this8 = this;

      this.colorData = rows;
      (0, _object.objectEach)(rows, function (colors, i) {
        var barCoords = _this8.getRangeBarCoordinates(i);

        if (barCoords) {
          _this8.updateRangeBarData(barCoords[0], barCoords[1], {
            colors: colors
          });
        }
      });
      this.hot.render();
    }
    /**
     * Updates the chart with a new year.
     *
     * @param {Number} year New chart year.
     */

  }, {
    key: "setYear",
    value: function setYear(year) {
      var newSettings = (0, _object.extend)(this.hot.getSettings().ganttChart, {
        startYear: year
      });
      this.hot.updateSettings({
        ganttChart: newSettings
      });
    }
    /**
     * AfterInit hook callback.
     *
     * @private
     */

  }, {
    key: "onAfterInit",
    value: function onAfterInit() {
      this.nestedHeadersPlugin = this.hot.getPlugin('nestedHeaders');
      this.applyDataSource();
    }
    /**
     * Prevents update settings loop when assigning the additional internal settings.
     *
     * @private
     */

  }, {
    key: "onUpdateSettings",
    value: function onUpdateSettings() {
      if (this.internalUpdateSettings) {
        this.applyRangeBarMetaCache();
        return;
      }

      _get(_getPrototypeOf(GanttChart.prototype), "onUpdateSettings", this).call(this);
    }
    /**
     * Destroys the plugin instance.
     */

  }, {
    key: "destroy",
    value: function destroy() {
      if (this.hotSource) {
        this.dataFeed.removeSourceHotHooks(this.hotSource);
      }

      _get(_getPrototypeOf(GanttChart.prototype), "destroy", this).call(this);
    }
  }]);

  return GanttChart;
}(_base.default);

(0, _plugins.registerPlugin)('ganttChart', GanttChart);
var _default = GanttChart;
exports.default = _default;