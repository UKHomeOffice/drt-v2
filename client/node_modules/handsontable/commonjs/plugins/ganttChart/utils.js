"use strict";

require("core-js/modules/es.array.concat");

require("core-js/modules/es.function.name");

require("core-js/modules/es.object.to-string");

require("core-js/modules/es.regexp.to-string");

exports.__esModule = true;
exports.getMixedMonthObject = getMixedMonthObject;
exports.getMixedMonthName = getMixedMonthName;
exports.getShorthand = getShorthand;
exports.getStartDate = getStartDate;
exports.getEndDate = getEndDate;
exports.getAdditionalData = getAdditionalData;
exports.setStartDate = setStartDate;
exports.setEndDate = setEndDate;
exports.parseDate = parseDate;
exports.getDateYear = getDateYear;
exports.WEEK_LENGTH = exports.DEC_LENGTH = void 0;

/**
 * Day count for December.
 *
 * @type {Number}
 */
var DEC_LENGTH = 31;
/**
 * Day count for a week.
 *
 * @type {Number}
 */

exports.DEC_LENGTH = DEC_LENGTH;
var WEEK_LENGTH = 7;
/**
 * Generate a mixed month object.
 *
 * @private
 * @param {String} monthName The month name.
 * @param {Number} index Index for the mixed month.
 * @returns {Object} The month object.
 */

exports.WEEK_LENGTH = WEEK_LENGTH;

function getMixedMonthObject(monthName, index) {
  return {
    name: monthName,
    days: WEEK_LENGTH,
    daysBeforeFullWeeks: 0,
    daysAfterFullWeeks: 0,
    fullWeeks: 1,
    index: index
  };
}
/**
 * Generate the name for a mixed month.
 *
 * @private
 * @param {Number} afterMonthIndex Index of the month after the mixed one.
 * @param {Array} monthList List of the months.
 * @returns {String} Name for the mixed month.
 */


function getMixedMonthName(afterMonthIndex, monthList) {
  var mixedMonthName = null;
  var afterMonthShorthand = getShorthand(monthList[afterMonthIndex].name);
  var beforeMonthShorthand = afterMonthIndex > 0 ? getShorthand(monthList[afterMonthIndex - 1].name) : null;
  var firstMonthShorthand = getShorthand(monthList[0].name);
  var lastMonthShorthand = getShorthand(monthList[monthList.length - 1].name);

  if (afterMonthIndex > 0) {
    mixedMonthName = "".concat(beforeMonthShorthand, "/").concat(afterMonthShorthand);
  } else if (afterMonthIndex === monthList.length - 1) {
    mixedMonthName = "".concat(afterMonthShorthand, "/").concat(firstMonthShorthand);
  } else {
    mixedMonthName = "".concat(lastMonthShorthand, "/").concat(afterMonthShorthand);
  }

  return mixedMonthName;
}
/**
 * Get the three first letters from the provided month name.
 *
 * @private
 * @param {String} monthName The month name.
 * @returns {String} The three-lettered shorthand for the month name.
 */


function getShorthand(monthName) {
  var MONTH_SHORT_LEN = 3;
  return monthName.substring(0, MONTH_SHORT_LEN);
}
/**
 * Get the start date of the provided range bar.
 *
 * @param {Object} rangeBar The range bar object.
 * @returns {Date} The start date.
 */


function getStartDate(rangeBar) {
  return parseDate(Array.isArray(rangeBar) ? rangeBar[1] : rangeBar.startDate);
}
/**
 * Get the end date of the provided range bar.
 *
 * @param {Object} rangeBar The range bar object.
 * @returns {Date} The end date.
 */


function getEndDate(rangeBar) {
  return parseDate(Array.isArray(rangeBar) ? rangeBar[2] : rangeBar.endDate);
}
/**
 * Get the additional data object of the provided range bar.
 *
 * @param {Object} rangeBar The range bar object.
 * @returns {Object} The additional data object.
 */


function getAdditionalData(rangeBar) {
  return Array.isArray(rangeBar) ? rangeBar[3] : rangeBar.additionalData;
}
/**
 * Set the start date of the provided range bar.
 *
 * @param {Object} rangeBar The range bar object.
 * @param {Date} value The new start date value.
 */


function setStartDate(rangeBar, value) {
  if (Array.isArray(rangeBar)) {
    rangeBar[1] = value;
  } else {
    rangeBar.startDate = value;
  }
}
/**
 * Set the end date of the provided range bar.
 *
 * @param {Object} rangeBar The range bar object.
 * @param {Date} value The new end date value.
 */


function setEndDate(rangeBar, value) {
  if (Array.isArray(rangeBar)) {
    rangeBar[2] = value;
  } else {
    rangeBar.endDate = value;
  }
}
/**
 * Parse the provided date and check if it's valid.
 *
 * @param {String|Date} date Date string or object.
 * @returns {Date|null} Parsed Date object or null, if not a valid date string.
 */


function parseDate(date) {
  var newDate = date;

  if (newDate === null) {
    return null;
  }

  if (!(newDate instanceof Date)) {
    newDate = new Date(newDate);

    if (newDate.toString() === 'Invalid Date') {
      return null;
    }
  }

  return newDate;
}
/**
 * Get the year of the provided date.
 *
 * @param {Date|String} date Date to get the year from.
 * @returns {Number|null} The year from the provided date.
 */


function getDateYear(date) {
  var newDate = parseDate(date);
  return newDate ? newDate.getFullYear() : null;
}