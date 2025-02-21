"use strict";

require("core-js/modules/es.symbol");

require("core-js/modules/es.symbol.description");

require("core-js/modules/es.symbol.iterator");

require("core-js/modules/es.array.concat");

require("core-js/modules/es.array.index-of");

require("core-js/modules/es.array.iterator");

require("core-js/modules/es.array.slice");

require("core-js/modules/es.array.splice");

require("core-js/modules/es.function.name");

require("core-js/modules/es.object.keys");

require("core-js/modules/es.object.to-string");

require("core-js/modules/es.regexp.to-string");

require("core-js/modules/es.string.iterator");

require("core-js/modules/web.dom-collections.iterator");

exports.__esModule = true;
exports.default = void 0;

var _array = require("../../helpers/array");

var _object = require("../../helpers/object");

var _console = require("../../helpers/console");

var _utils = require("./utils");

function _slicedToArray(arr, i) { return _arrayWithHoles(arr) || _iterableToArrayLimit(arr, i) || _nonIterableRest(); }

function _nonIterableRest() { throw new TypeError("Invalid attempt to destructure non-iterable instance"); }

function _iterableToArrayLimit(arr, i) { if (!(Symbol.iterator in Object(arr) || Object.prototype.toString.call(arr) === "[object Arguments]")) { return; } var _arr = []; var _n = true; var _d = false; var _e = undefined; try { for (var _i = arr[Symbol.iterator](), _s; !(_n = (_s = _i.next()).done); _n = true) { _arr.push(_s.value); if (i && _arr.length === i) break; } } catch (err) { _d = true; _e = err; } finally { try { if (!_n && _i["return"] != null) _i["return"](); } finally { if (_d) throw _e; } } return _arr; }

function _arrayWithHoles(arr) { if (Array.isArray(arr)) return arr; }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

/**
 * This class handles the date-related calculations for the GanttChart plugin.
 *
 * @plugin GanttChart
 */
var DateCalculator =
/*#__PURE__*/
function () {
  function DateCalculator(_ref) {
    var year = _ref.year,
        _ref$allowSplitWeeks = _ref.allowSplitWeeks,
        allowSplitWeeks = _ref$allowSplitWeeks === void 0 ? true : _ref$allowSplitWeeks,
        _ref$hideDaysBeforeFu = _ref.hideDaysBeforeFullWeeks,
        hideDaysBeforeFullWeeks = _ref$hideDaysBeforeFu === void 0 ? false : _ref$hideDaysBeforeFu,
        _ref$hideDaysAfterFul = _ref.hideDaysAfterFullWeeks,
        hideDaysAfterFullWeeks = _ref$hideDaysAfterFul === void 0 ? false : _ref$hideDaysAfterFul;

    _classCallCheck(this, DateCalculator);

    /**
     * Year to base calculations on.
     *
     * @type {Number}
     */
    this.year = year;
    /**
     * First day of the week.
     *
     * @type {String}
     */

    this.firstWeekDay = 'monday';
    /**
     * The current `allowSplitWeeks` option state.
     */

    this.allowSplitWeeks = allowSplitWeeks;
    /**
     * The current `hideDaysBeforeFullWeeks` option state.
     */

    this.hideDaysBeforeFullWeeks = hideDaysBeforeFullWeeks;
    /**
     * The current `hideDaysAfterFullWeeks` option state.
     */

    this.hideDaysAfterFullWeeks = hideDaysAfterFullWeeks;
    /**
     * Number of week sections (full weeks + incomplete week blocks in months).
     *
     * @type {Number}
     */

    this.weekSectionCount = 0;
    /**
     * Cache of lists of months and their week/day related information.
     * It's categorized by year, so month information for a certain year is stored under `this.monthListCache[year]`.
     *
     * @type {Object}
     */

    this.monthListCache = {};
    /**
     * Object containing references to the year days and their corresponding columns.
     *
     * @type {Object}
     */

    this.daysInColumns = {};
    this.calculateWeekStructure();
  }
  /**
   * Set the year as a base for calculations.
   *
   * @param {Number} year
   */


  _createClass(DateCalculator, [{
    key: "setYear",
    value: function setYear(year) {
      this.year = year;
      this.monthListCache[year] = this.calculateMonthData(year);
      this.calculateWeekStructure(year);
    }
    /**
     * Set the first week day.
     *
     * @param {String} day Day of the week. Available options: 'monday' or 'sunday'.
     */

  }, {
    key: "setFirstWeekDay",
    value: function setFirstWeekDay(day) {
      var lowercaseDay = day.toLowerCase();

      if (lowercaseDay !== 'monday' && lowercaseDay !== 'sunday') {
        (0, _console.warn)('First day of the week must be set to either Monday or Sunday');
      }

      this.firstWeekDay = lowercaseDay;
      this.calculateWeekStructure();
    }
    /**
     * Count week sections (full weeks + incomplete weeks in the months).
     *
     * @returns {Number} Week section count.
     */

  }, {
    key: "countWeekSections",
    value: function countWeekSections() {
      return this.weekSectionCount;
    }
    /**
     * Get the first week day.
     *
     * @returns {String}
     */

  }, {
    key: "getFirstWeekDay",
    value: function getFirstWeekDay() {
      return this.firstWeekDay;
    }
    /**
     * Get the currently applied year.
     *
     * @returns {Number}
     */

  }, {
    key: "getYear",
    value: function getYear() {
      return this.year;
    }
    /**
     * Get month list along with the month information.
     *
     * @param {Number} [year] Year for the calculation.
     * @returns {Array}
     */

  }, {
    key: "getMonthList",
    value: function getMonthList() {
      var year = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : this.year;

      if (!this.monthListCache[year]) {
        this.monthListCache[year] = this.calculateMonthData(year);
      }

      return this.monthListCache[year];
    }
    /**
     * Get month lists for all years declared in the range bars.
     *
     * @returns {Object}
     */

  }, {
    key: "getFullMonthList",
    value: function getFullMonthList() {
      return this.monthListCache;
    }
    /**
     * Convert a date to a column number.
     *
     * @param {String|Date} date
     * @returns {Number|Boolean}
     */

  }, {
    key: "dateToColumn",
    value: function dateToColumn(date) {
      var convertedDate = (0, _utils.parseDate)(date);

      if (!convertedDate) {
        return false;
      }

      var month = convertedDate.getMonth();
      var day = convertedDate.getDate() - 1;
      var year = convertedDate.getFullYear();
      return this.getWeekColumn(day, month, year);
    }
    /**
     * Get the column index for the provided day and month indexes.
     *
     * @private
     * @param {Number} dayIndex The index of the day.
     * @param {Number} monthIndex The index of the month.
     * @param {Number} [year] Year for the calculation.
     * @returns {Number} Returns the column index.
     */

  }, {
    key: "getWeekColumn",
    value: function getWeekColumn(dayIndex, monthIndex) {
      var year = arguments.length > 2 && arguments[2] !== undefined ? arguments[2] : this.getYear();
      var resultColumn = null;
      var monthCacheArray = this.getMonthCacheArray(monthIndex, year);
      (0, _array.arrayEach)(monthCacheArray, function (monthCache) {
        (0, _object.objectEach)(monthCache, function (column, index) {
          if (column.indexOf(dayIndex + 1) > -1) {
            resultColumn = parseInt(index, 10);
            return false;
          }
        });

        if (resultColumn) {
          return false;
        }
      });
      return resultColumn;
    }
    /**
     * Get the cached day array for the provided month.
     *
     * @private
     * @param {Number} monthIndex Index of the Month.
     * @param {Number} [year] Year for the calculation.
     * @returns {Array}
     */

  }, {
    key: "getMonthCacheArray",
    value: function getMonthCacheArray(monthIndex) {
      var _this = this;

      var year = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : this.getYear();
      var monthList = this.getMonthList(year);
      var resultArray = [];

      if (this.allowSplitWeeks) {
        resultArray.push(this.daysInColumns[year][monthIndex]);
      } else {
        var fullMonthCount = -1;
        (0, _object.objectEach)(this.daysInColumns[year], function (month, i) {
          var monthObject = monthList[i];

          if (Object.keys(month).length > 1) {
            fullMonthCount += 1;
          }

          if (fullMonthCount === monthIndex) {
            if (monthObject.daysBeforeFullWeeks > 0) {
              resultArray.push(_this.daysInColumns[year][parseInt(i, 10) - 1]);
            }

            resultArray.push(month);

            if (monthObject.daysAfterFullWeeks > 0) {
              resultArray.push(_this.daysInColumns[year][parseInt(i, 10) + 1]);
            }

            return false;
          }
        });
      }

      return resultArray;
    }
    /**
     * Convert a column index to a certain date.
     *
     * @param {Number} column Column index.
     * @param {Number} [year] Year to be used.
     * @returns {Object} Object in a form of {start: startDate, end: endDate}
     */

  }, {
    key: "columnToDate",
    value: function columnToDate(column) {
      var year = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : this.getYear();
      var month = null;
      (0, _object.objectEach)(this.daysInColumns[year], function (monthCache, index) {
        if (monthCache[column]) {
          month = index;
          return false;
        }
      });
      var monthSection = this.daysInColumns[year][month][column];

      if (monthSection.length === 1) {
        var resultingDate = new Date(year, month, monthSection[0]);
        return {
          start: resultingDate,
          end: resultingDate
        };
      }

      return {
        start: new Date(year, month, monthSection[0]),
        end: new Date(year, month, monthSection[monthSection.length - 1])
      };
    }
    /**
     * Check if the provided date is a starting or an ending day of a week.
     *
     * @private
     * @param {Date|String} date
     * @returns {Array|Boolean} Returns null, if an invalid date was provided or an array of results ( [1,0] => is on the beginning of the week, [0,1] => is on the end of the week).
     */

  }, {
    key: "isOnTheEdgeOfWeek",
    value: function isOnTheEdgeOfWeek(date) {
      var _this2 = this;

      var convertedDate = (0, _utils.parseDate)(date);

      if (!convertedDate) {
        return null;
      }

      var month = convertedDate.getMonth();
      var day = convertedDate.getDate() - 1;
      var year = convertedDate.getFullYear();
      var monthCacheArray = this.getMonthCacheArray(month, year);
      var isOnTheEdgeOfWeek = false;
      (0, _array.arrayEach)(monthCacheArray, function (monthCache) {
        (0, _object.objectEach)(monthCache, function (column) {
          if (!_this2.allowSplitWeeks && column.length !== 7) {
            if (day === 0 || day === new Date(convertedDate.getYear(), convertedDate.getMonth() + 1, 0).getDate() - 1) {
              return true;
            }
          }

          var indexOfDay = column.indexOf(day + 1);

          if (indexOfDay === 0) {
            isOnTheEdgeOfWeek = [1, 0];
            return false;
          } else if (indexOfDay === column.length - 1) {
            isOnTheEdgeOfWeek = [0, 1];
            return false;
          }
        }); // break the iteration

        if (isOnTheEdgeOfWeek) {
          return false;
        }
      });
      return isOnTheEdgeOfWeek;
    }
    /**
     * Generate headers for the year structure.
     *
     * @private
     * @param {String} type Granulation type ('months'/'weeks'/'days')
     * @param {Function|null} weekHeaderGenerator Function generating the looks of the week headers.
     * @param {Number} [year=this.year] The year for the calculation.
     * @returns {Array} The header array
     */

  }, {
    key: "generateHeaderSet",
    value: function generateHeaderSet(type, weekHeaderGenerator) {
      var _this3 = this;

      var year = arguments.length > 2 && arguments[2] !== undefined ? arguments[2] : this.year;
      var monthList = this.getMonthList(year);
      var headers = [];
      (0, _object.objectEach)(monthList, function (month, index) {
        var areDaysBeforeFullWeeks = month.daysBeforeFullWeeks > 0 ? 1 : 0;
        var areDaysAfterFullWeeks = month.daysAfterFullWeeks > 0 ? 1 : 0;
        var areDaysBeforeFullWeeksVisible = _this3.hideDaysBeforeFullWeeks ? 0 : areDaysBeforeFullWeeks;
        var areDaysAfterFullWeeksVisible = _this3.hideDaysAfterFullWeeks ? 0 : areDaysAfterFullWeeks;
        var headerCount = month.fullWeeks + (_this3.allowSplitWeeks ? areDaysBeforeFullWeeksVisible + areDaysAfterFullWeeksVisible : 0);
        var monthNumber = parseInt(index, 10);
        var headerLabel = '';

        if (type === 'months') {
          headers.push({
            label: month.name,
            colspan: headerCount
          });
        } else if (type === 'weeks') {
          for (var i = 0; i < headerCount; i++) {
            var start = null;
            var end = null; // Mixed month's only column

            if (!_this3.allowSplitWeeks && month.fullWeeks === 1) {
              var _this3$getWeekColumnR = _this3.getWeekColumnRange({
                monthObject: month,
                monthNumber: monthNumber,
                headerIndex: i,
                headerCount: headerCount,
                areDaysBeforeFullWeeksVisible: areDaysBeforeFullWeeksVisible,
                areDaysAfterFullWeeksVisible: areDaysAfterFullWeeksVisible,
                mixedMonth: true,
                year: year
              });

              var _this3$getWeekColumnR2 = _slicedToArray(_this3$getWeekColumnR, 2);

              start = _this3$getWeekColumnR2[0];
              end = _this3$getWeekColumnR2[1];
            } else {
              var _this3$getWeekColumnR3 = _this3.getWeekColumnRange({
                monthObject: month,
                monthNumber: monthNumber,
                headerIndex: i,
                areDaysBeforeFullWeeksVisible: areDaysBeforeFullWeeksVisible,
                areDaysAfterFullWeeksVisible: areDaysAfterFullWeeksVisible,
                headerCount: headerCount,
                year: year
              });

              var _this3$getWeekColumnR4 = _slicedToArray(_this3$getWeekColumnR3, 2);

              start = _this3$getWeekColumnR4[0];
              end = _this3$getWeekColumnR4[1];
            }

            if (start === end) {
              headerLabel = "".concat(start);
            } else {
              headerLabel = "".concat(start, " -  ").concat(end);
            }

            headers.push(weekHeaderGenerator ? weekHeaderGenerator.call(_this3, start, end) : headerLabel);

            _this3.addDaysToCache(monthNumber, headers.length - 1, start, end, year);
          }
        }
      });
      return headers;
    }
    /**
     * Get the week column range.
     *
     * @private
     * @param {Object} options The options object.
     * @param {Object} options.monthObject The month object.
     * @param {Number} options.monthNumber Index of the month.
     * @param {Number} options.headerIndex Index of the header.
     * @param {Boolean} options.areDaysBeforeFullWeeksVisible `true` if the days before full weeks are to be visible.
     * @param {Boolean} options.areDaysAfterFullWeeksVisible `true` if the days after full weeks are to be visible.
     * @param {Number} options.headerCount Number of headers to be generated for the provided month.
     * @param {Boolean} [options.mixedMonth=false] `true` if the header is the single header of a mixed month.
     * @param {Number} [year] Year for the calculation.
     * @returns {Array}
     */

  }, {
    key: "getWeekColumnRange",
    value: function getWeekColumnRange(_ref2) {
      var monthObject = _ref2.monthObject,
          monthNumber = _ref2.monthNumber,
          headerIndex = _ref2.headerIndex,
          headerCount = _ref2.headerCount,
          areDaysBeforeFullWeeksVisible = _ref2.areDaysBeforeFullWeeksVisible,
          areDaysAfterFullWeeksVisible = _ref2.areDaysAfterFullWeeksVisible,
          _ref2$mixedMonth = _ref2.mixedMonth,
          mixedMonth = _ref2$mixedMonth === void 0 ? false : _ref2$mixedMonth,
          _ref2$year = _ref2.year,
          year = _ref2$year === void 0 ? this.year : _ref2$year;
      var monthList = this.getMonthList(year);
      var allowSplitWeeks = this.allowSplitWeeks;
      var start = null;
      var end = null;

      if (mixedMonth) {
        if (monthNumber === 0) {
          end = monthList[monthNumber + 1].daysBeforeFullWeeks;
          start = _utils.DEC_LENGTH - (_utils.WEEK_LENGTH - end) + 1;
        } else if (monthNumber === monthList.length - 1) {
          end = _utils.WEEK_LENGTH - monthList[monthNumber - 1].daysAfterFullWeeks;
          start = monthList[monthNumber - 1].days - monthList[monthNumber - 1].daysAfterFullWeeks + 1;
        } else {
          end = monthList[monthNumber + 1].daysBeforeFullWeeks;
          start = monthList[monthNumber - 1].days - (_utils.WEEK_LENGTH - end) + 1;
        }
      } else if (allowSplitWeeks && areDaysBeforeFullWeeksVisible && headerIndex === 0) {
        start = headerIndex + 1;
        end = monthObject.daysBeforeFullWeeks;
      } else if (allowSplitWeeks && areDaysAfterFullWeeksVisible && headerIndex === headerCount - 1) {
        start = monthObject.days - monthObject.daysAfterFullWeeks + 1;
        end = monthObject.days;
      } else {
        start = null;

        if (allowSplitWeeks) {
          start = monthObject.daysBeforeFullWeeks + (headerIndex - areDaysBeforeFullWeeksVisible) * _utils.WEEK_LENGTH + 1;
        } else {
          start = monthObject.daysBeforeFullWeeks + headerIndex * _utils.WEEK_LENGTH + 1;
        }

        end = start + _utils.WEEK_LENGTH - 1;
      }

      return [start, end];
    }
    /**
     * Add days to the column/day cache.
     *
     * @private
     * @param {Number} monthNumber Index of the month.
     * @param {Number} columnNumber Index of the column.
     * @param {Number} start First day in the column.
     * @param {Number} end Last day in the column.
     * @param {Number} [year] Year to process.
     */

  }, {
    key: "addDaysToCache",
    value: function addDaysToCache(monthNumber, columnNumber, start, end) {
      var year = arguments.length > 4 && arguments[4] !== undefined ? arguments[4] : this.getYear();

      if (!this.daysInColumns[year]) {
        this.daysInColumns[year] = {};
      }

      if (!this.daysInColumns[year][monthNumber]) {
        this.daysInColumns[year][monthNumber] = {};
      }

      if (!this.daysInColumns[year][monthNumber][columnNumber]) {
        this.daysInColumns[year][monthNumber][columnNumber] = [];
      }

      if (start <= end) {
        for (var dayIndex = start; dayIndex <= end; dayIndex++) {
          this.daysInColumns[year][monthNumber][columnNumber].push(dayIndex);
        }
      } else {
        var previousMonthDaysCount = monthNumber - 1 >= 0 ? this.countMonthDays(monthNumber) : 31;

        for (var _dayIndex = start; _dayIndex <= previousMonthDaysCount; _dayIndex++) {
          this.daysInColumns[year][monthNumber][columnNumber].push(_dayIndex);
        }

        for (var _dayIndex2 = 1; _dayIndex2 <= end; _dayIndex2++) {
          this.daysInColumns[year][monthNumber][columnNumber].push(_dayIndex2);
        }
      }
    }
    /**
     * Check if the provided dates can be used in a range bar.
     *
     * @param {Date|String} startDate Range start date.
     * @param {Date|String} endDate Range end date.
     * @returns {Boolean}
     */

  }, {
    key: "isValidRangeBarData",
    value: function isValidRangeBarData(startDate, endDate) {
      var startDateParsed = (0, _utils.parseDate)(startDate);
      var endDateParsed = (0, _utils.parseDate)(endDate);
      return startDateParsed && endDateParsed && startDateParsed.getTime() <= endDateParsed.getTime();
    }
    /**
     * Calculate the month/day related information.
     *
     * @param {Number} [year] Year to be used.
     * @returns {Array}
     */

  }, {
    key: "calculateMonthData",
    value: function calculateMonthData() {
      var year = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : this.year;
      return [{
        name: 'January',
        days: 31
      }, {
        name: 'February',
        days: new Date(year, 2, 0).getDate()
      }, {
        name: 'March',
        days: 31
      }, {
        name: 'April',
        days: 30
      }, {
        name: 'May',
        days: 31
      }, {
        name: 'June',
        days: 30
      }, {
        name: 'July',
        days: 31
      }, {
        name: 'August',
        days: 31
      }, {
        name: 'September',
        days: 30
      }, {
        name: 'October',
        days: 31
      }, {
        name: 'November',
        days: 30
      }, {
        name: 'December',
        days: 31
      }].slice(0);
    }
    /**
     * Count the number of months.
     *
     * @param {Number} [year] Year to be used.
     * @returns {Number}
     */

  }, {
    key: "countMonths",
    value: function countMonths() {
      var year = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : this.getYear();
      return this.monthListCache[year].length;
    }
    /**
     * Count days in a month.
     *
     * @param {Number} month Month index, where January = 1, February = 2, etc.
     * @param {Number} [year] Year to be used.
     * @returns {Number}
     */

  }, {
    key: "countMonthDays",
    value: function countMonthDays(month) {
      var year = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : this.getYear();
      return this.monthListCache[year][month - 1].days;
    }
    /**
     * Count full weeks in a month.
     *
     * @param {Number} month Month index, where January = 1, February = 2, etc.
     * @param {Number} [year] Year to be used.
     * @returns {Number}
     */

  }, {
    key: "countMonthFullWeeks",
    value: function countMonthFullWeeks(month) {
      var year = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : this.getYear();
      return this.monthListCache[year][month - 1].fullWeeks;
    }
    /**
     * Calculate week structure within defined months.
     *
     * @private
     * @param {Number} [year] Year for the calculation.
     */

  }, {
    key: "calculateWeekStructure",
    value: function calculateWeekStructure() {
      var _this4 = this;

      var year = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : this.getYear();
      this.monthListCache[year] = this.calculateMonthData(year);
      var firstWeekDay = this.getFirstWeekDay();
      var monthList = this.getMonthList(year);
      var mixedMonthToAdd = [];
      var daysBeforeFullWeeksRatio = this.hideDaysBeforeFullWeeks ? 0 : 1;
      var daysAfterFullWeeksRatio = this.hideDaysAfterFullWeeks ? 0 : 1;
      var weekOffset = 0;
      var weekSectionCount = 0;

      if (firstWeekDay === 'monday') {
        weekOffset = 1;
      }

      (0, _array.arrayEach)(monthList, function (currentMonth, monthIndex) {
        var firstMonthDay = new Date(year, monthIndex, 1).getDay();
        var mixedMonthsAdded = 0;
        currentMonth.daysBeforeFullWeeks = (7 - firstMonthDay + weekOffset) % 7;

        if (!_this4.allowSplitWeeks && currentMonth.daysBeforeFullWeeks) {
          mixedMonthToAdd.push((0, _utils.getMixedMonthObject)((0, _utils.getMixedMonthName)(monthIndex, monthList), monthIndex));
          mixedMonthsAdded += 1;
        }

        currentMonth.fullWeeks = Math.floor((currentMonth.days - currentMonth.daysBeforeFullWeeks) / 7);
        currentMonth.daysAfterFullWeeks = currentMonth.days - currentMonth.daysBeforeFullWeeks - 7 * currentMonth.fullWeeks;

        if (!_this4.allowSplitWeeks) {
          if (monthIndex === monthList.length - 1 && currentMonth.daysAfterFullWeeks) {
            mixedMonthToAdd.push((0, _utils.getMixedMonthObject)((0, _utils.getMixedMonthName)(monthIndex, monthList), null));
            mixedMonthsAdded += 1;
          }

          weekSectionCount += currentMonth.fullWeeks + mixedMonthsAdded;
        } else {
          var numberOfPartialWeeksBefore = daysBeforeFullWeeksRatio * (currentMonth.daysBeforeFullWeeks ? 1 : 0);
          var numberOfPartialWeeksAfter = daysAfterFullWeeksRatio * (currentMonth.daysAfterFullWeeks ? 1 : 0);
          weekSectionCount += currentMonth.fullWeeks + numberOfPartialWeeksBefore + numberOfPartialWeeksAfter;
        }
      });
      (0, _array.arrayEach)(mixedMonthToAdd, function (monthObject, monthIndex) {
        var index = monthObject.index;
        delete monthObject.index;

        _this4.addMixedMonth(index === null ? index : monthIndex + index, monthObject, year);
      });

      if (year === this.getYear()) {
        this.weekSectionCount = weekSectionCount;
      }
    }
    /**
     * Add a mixed (e.g. 'Jan/Feb') month to the month list.
     *
     * @private
     * @param {Number} index Index for the month.
     * @param {Object} monthObject The month object.
     * @param {Number} [year] Year for the calculation.
     */

  }, {
    key: "addMixedMonth",
    value: function addMixedMonth(index, monthObject, year) {
      if (index === null) {
        this.monthListCache[year].push(monthObject);
      } else {
        this.monthListCache[year].splice(index, 0, monthObject);
      }
    }
  }]);

  return DateCalculator;
}();

var _default = DateCalculator;
exports.default = _default;