"use strict";

require("core-js/modules/es.array.concat");

require("core-js/modules/es.object.to-string");

require("core-js/modules/es.regexp.to-string");

require("core-js/modules/web.timers");

exports.__esModule = true;
exports.default = void 0;

var _object = require("../../helpers/object");

var _array = require("../../helpers/array");

var _number = require("../../helpers/number");

var _utils = require("./utils");

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

/**
 * This class handles the data-related calculations for the GanttChart plugin.
 *
 * @plugin GanttChart
 */
var GanttChartDataFeed =
/*#__PURE__*/
function () {
  function GanttChartDataFeed(chartInstance, data, startDateColumn, endDateColumn, additionalData, asyncUpdates) {
    _classCallCheck(this, GanttChartDataFeed);

    this.data = data;
    this.chartInstance = chartInstance;
    this.chartPlugin = this.chartInstance.getPlugin('ganttChart');
    this.hotSource = null;
    this.sourceHooks = {};
    this.ongoingAsync = false;
    this.applyData(data, startDateColumn, endDateColumn, additionalData, asyncUpdates || false);
  }
  /**
   * Parse data accordingly to it's type (HOT instance / data object).
   *
   * @param {Object} data The source Handsontable instance or a data object.
   * @param {Number} startDateColumn Index of the column containing the start dates.
   * @param {Number} endDateColumn Index of the column containing the end dates.
   * @param {Object} additionalData Object containing column and label information about additional data passed to the Gantt Plugin.
   * @param {Boolean} asyncUpdates If set to true, the source instance updates will be applied asynchronously.
   */


  _createClass(GanttChartDataFeed, [{
    key: "applyData",
    value: function applyData(data, startDateColumn, endDateColumn, additionalData, asyncUpdates) {
      if (Object.prototype.toString.call(data) === '[object Array]') {
        if (data.length > 1) {
          this.chartInstance.alter('insert_row', 0, data.length - 1, "".concat(this.pluginName, ".loadData"));
        }

        this.loadData(data);
      } else if (data instanceof this.chartInstance.constructor) {
        var sourceRowCount = data.countRows();

        if (sourceRowCount > 1) {
          this.chartInstance.alter('insert_row', 0, sourceRowCount - 1, "".concat(this.pluginName, ".loadData"));
        }

        this.bindWithHotInstance(data, startDateColumn, endDateColumn, additionalData, asyncUpdates);
      }
    }
    /**
     * Make another Handsontable instance be a live feed for the gantt chart.
     *
     * @param {Object} instance The source Handsontable instance.
     * @param {Number} startDateColumn Index of the column containing the start dates.
     * @param {Number} endDateColumn Index of the column containing the end dates.
     * @param {Object} additionalData Object containing column and label information about additional data passed to the
     * Gantt Plugin. See the example for more details.
     * @param {Boolean} asyncUpdates If set to true, the source instance updates will be applied asynchronously.
     *
     * @example
     * ```js
     * hot.getPlugin('ganttChart').bindWithHotInstance(sourceInstance, 4, 5, {
     *  vendor: 0, // data labeled 'vendor' is stored in the first sourceInstance column.
     *  format: 1, // data labeled 'format' is stored in the second sourceInstance column.
     *  market: 2 // data labeled 'market' is stored in the third sourceInstance column.
     * });
     * ```
     */

  }, {
    key: "bindWithHotInstance",
    value: function bindWithHotInstance(instance, startDateColumn, endDateColumn, additionalData, asyncUpdates) {
      this.hotSource = {
        instance: instance,
        startColumn: startDateColumn,
        endColumn: endDateColumn,
        additionalData: additionalData,
        asyncUpdates: asyncUpdates
      };
      this.addSourceHotHooks();
      this.asyncCall(this.updateFromSource);
    }
    /**
     * Run the provided function asynchronously.
     *
     * @param {Function} func
     */

  }, {
    key: "asyncCall",
    value: function asyncCall(func) {
      var _this = this;

      if (!this.hotSource.asyncUpdates) {
        func.call(this);
        return;
      }

      this.asyncStart();
      setTimeout(function () {
        func.call(_this);

        _this.asyncEnd();
      }, 0);
    }
  }, {
    key: "asyncStart",
    value: function asyncStart() {
      this.ongoingAsync = true;
    }
  }, {
    key: "asyncEnd",
    value: function asyncEnd() {
      this.ongoingAsync = false;
    }
    /**
     * Add hooks to the source Handsontable instance.
     *
     * @private
     */

  }, {
    key: "addSourceHotHooks",
    value: function addSourceHotHooks() {
      var _this2 = this;

      this.sourceHooks = {
        afterLoadData: function afterLoadData() {
          return _this2.onAfterSourceLoadData();
        },
        afterChange: function afterChange(changes) {
          return _this2.onAfterSourceChange(changes);
        },
        afterColumnSort: function afterColumnSort() {
          return _this2.onAfterColumnSort();
        }
      };
      this.hotSource.instance.addHook('afterLoadData', this.sourceHooks.afterLoadData);
      this.hotSource.instance.addHook('afterChange', this.sourceHooks.afterChange);
      this.hotSource.instance.addHook('afterColumnSort', this.sourceHooks.afterColumnSort);
    }
    /**
     * Remove hooks from the source Handsontable instance.
     *
     * @private
     * @param {Object} hotSource The source Handsontable instance object.
     */

  }, {
    key: "removeSourceHotHooks",
    value: function removeSourceHotHooks(hotSource) {
      if (this.sourceHooks.afterLoadData) {
        hotSource.instance.removeHook('afterLoadData', this.sourceHooks.afterLoadData);
      }

      if (this.sourceHooks.afterChange) {
        hotSource.instance.removeHook('afterChange', this.sourceHooks.afterChange);
      }

      if (this.sourceHooks.afterColumnSort) {
        hotSource.instance.removeHook('afterColumnSort', this.sourceHooks.afterColumnSort);
      }
    }
    /**
     * Get data from the source Handsontable instance.
     *
     * @param {Number} [row] Source Handsontable instance row.
     * @returns {Array}
     */

  }, {
    key: "getDataFromSource",
    value: function getDataFromSource(row) {
      var additionalObjectData = {};
      var hotSource = this.hotSource;
      var sourceHotRows;
      var rangeBarData = [];

      if (row === void 0) {
        sourceHotRows = hotSource.instance.getData(0, 0, hotSource.instance.countRows() - 1, hotSource.instance.countCols() - 1);
      } else {
        sourceHotRows = [];
        sourceHotRows[row] = hotSource.instance.getDataAtRow(row);
      }

      var _loop = function _loop(i, dataLength) {
        additionalObjectData = {};
        var currentRow = sourceHotRows[i];

        if (currentRow[hotSource.startColumn] === null || currentRow[hotSource.startColumn] === '') {
          /* eslint-disable no-continue */
          return "continue";
        }
        /* eslint-disable no-loop-func */


        (0, _object.objectEach)(hotSource.additionalData, function (prop, j) {
          additionalObjectData[j] = currentRow[prop];
        });
        rangeBarData.push([i, currentRow[hotSource.startColumn], currentRow[hotSource.endColumn], additionalObjectData, i]);
      };

      for (var i = row || 0, dataLength = sourceHotRows.length; i < (row ? row + 1 : dataLength); i++) {
        var _ret = _loop(i, dataLength);

        if (_ret === "continue") continue;
      }

      return rangeBarData;
    }
    /**
     * Update the Gantt Chart-enabled Handsontable instance with the data from the source Handsontable instance.
     *
     * @param {Number} [row] Index of the row which needs updating.
     */

  }, {
    key: "updateFromSource",
    value: function updateFromSource(row) {
      var dataFromSource = this.getDataFromSource(row);

      if (!row && isNaN(row)) {
        this.chartPlugin.clearRangeBars();
        this.chartPlugin.clearRangeList();
      }

      this.loadData(dataFromSource);
      this.chartInstance.render();
    }
    /**
     * Load chart data to the Handsontable instance.
     *
     * @param {Array} data Array of objects containing the range data.
     *
     * @example
     * ```js
     * [
     *  {
     *    additionalData: {vendor: 'Vendor One', format: 'Posters', market: 'New York, NY'},
     *    startDate: '1/5/2015',
     *    endDate: '1/20/2015'
     *  },
     *  {
     *    additionalData: {vendor: 'Vendor Two', format: 'Malls', market: 'Los Angeles, CA'},
     *    startDate: '1/11/2015',
     *    endDate: '1/29/2015'
     *  }
     * ]
     * ```
     */

  }, {
    key: "loadData",
    value: function loadData(data) {
      var _this3 = this;

      var allBars = [];
      (0, _array.arrayEach)(data, function (bar, i) {
        bar.row = i;

        var bars = _this3.splitRangeIfNeeded(bar);

        allBars = allBars.concat(bars);
      });
      (0, _array.arrayEach)(allBars, function (bar) {
        _this3.chartPlugin.addRangeBar(bar.row, (0, _utils.getStartDate)(bar), (0, _utils.getEndDate)(bar), (0, _utils.getAdditionalData)(bar));

        delete bar.row;
      });
    }
    /**
     * Split the provided range into maximum-year-long chunks.
     *
     * @param {Object} bar The range bar object.
     * @returns {Array} An array of slip chunks (or a single-element array, if no splicing occured)
     */

  }, {
    key: "splitRangeIfNeeded",
    value: function splitRangeIfNeeded(bar) {
      var splitBars = [];
      var startDate = new Date((0, _utils.getStartDate)(bar));
      var endDate = new Date((0, _utils.getEndDate)(bar));

      if (typeof startDate === 'string' || typeof endDate === 'string') {
        return false;
      }

      var startYear = startDate.getFullYear();
      var endYear = endDate.getFullYear();

      if (startYear === endYear) {
        return [bar];
      }

      (0, _number.rangeEach)(startYear, endYear, function (year) {
        var newBar = (0, _object.clone)(bar);

        if (year !== startYear) {
          (0, _utils.setStartDate)(newBar, "01/01/".concat(year));
        }

        if (year !== endYear) {
          (0, _utils.setEndDate)(newBar, "12/31/".concat(year));
        }

        splitBars.push(newBar);
      });
      return splitBars;
    }
    /**
     * afterChange hook callback for the source Handsontable instance.
     *
     * @private
     * @param {Array} changes List of changes.
     */

  }, {
    key: "onAfterSourceChange",
    value: function onAfterSourceChange(changes) {
      var _this4 = this;

      this.asyncCall(function () {
        if (!changes) {
          return;
        }

        var changesByRows = {};

        for (var i = 0, changesLength = changes.length; i < changesLength; i++) {
          var currentChange = changes[i];
          var row = parseInt(currentChange[0], 10);
          var col = parseInt(currentChange[1], 10);

          if (!changesByRows[row]) {
            changesByRows[row] = {};
          }

          changesByRows[row][col] = [currentChange[2], currentChange[3]];
        }

        (0, _object.objectEach)(changesByRows, function (prop, i) {
          var row = parseInt(i, 10);

          if (_this4.chartPlugin.getRangeBarCoordinates(row)) {
            _this4.chartPlugin.removeRangeBarByColumn(row, _this4.chartPlugin.rangeList[row][1]);
          }

          _this4.updateFromSource(i);
        });
      });
    }
    /**
     * afterLoadData hook callback for the source Handsontable instance.
     *
     * @private
     */

  }, {
    key: "onAfterSourceLoadData",
    value: function onAfterSourceLoadData() {
      var _this5 = this;

      this.asyncCall(function () {
        _this5.chartPlugin.removeAllRangeBars();

        _this5.updateFromSource();
      });
    }
    /**
     * afterColumnSort hook callback for the source Handsontable instance.
     *
     * @private
     */

  }, {
    key: "onAfterColumnSort",
    value: function onAfterColumnSort() {
      var _this6 = this;

      this.asyncCall(function () {
        _this6.chartPlugin.removeAllRangeBars();

        _this6.updateFromSource();
      });
    }
  }]);

  return GanttChartDataFeed;
}();

var _default = GanttChartDataFeed;
exports.default = _default;