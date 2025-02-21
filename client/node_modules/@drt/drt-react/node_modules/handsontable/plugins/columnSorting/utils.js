"use strict";

exports.__esModule = true;
exports.areValidSortStates = areValidSortStates;
exports.createDateTimeCompareFunction = createDateTimeCompareFunction;
exports.getHeaderSpanElement = getHeaderSpanElement;
exports.getNextSortOrder = getNextSortOrder;
exports.isFirstLevelColumnHeader = isFirstLevelColumnHeader;
exports.wasHeaderClickedProperly = wasHeaderClickedProperly;
require("core-js/modules/es.set.difference.v2.js");
require("core-js/modules/es.set.intersection.v2.js");
require("core-js/modules/es.set.is-disjoint-from.v2.js");
require("core-js/modules/es.set.is-subset-of.v2.js");
require("core-js/modules/es.set.is-superset-of.v2.js");
require("core-js/modules/es.set.symmetric-difference.v2.js");
require("core-js/modules/es.set.union.v2.js");
require("core-js/modules/esnext.iterator.constructor.js");
require("core-js/modules/esnext.iterator.map.js");
require("core-js/modules/esnext.iterator.some.js");
var _moment = _interopRequireDefault(require("moment"));
var _object = require("../../helpers/object");
var _event = require("../../helpers/dom/event");
var _mixed = require("../../helpers/mixed");
var _sortService = require("./sortService");
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
const ASC_SORT_STATE = exports.ASC_SORT_STATE = 'asc';
const DESC_SORT_STATE = exports.DESC_SORT_STATE = 'desc';
const HEADER_SPAN_CLASS = exports.HEADER_SPAN_CLASS = 'colHeader';

/**
 * Get if column state is valid.
 *
 * @param {number} columnState Particular column state.
 * @returns {boolean}
 */
function isValidColumnState(columnState) {
  if ((0, _object.isObject)(columnState) === false) {
    return false;
  }
  const {
    column,
    sortOrder
  } = columnState;
  return Number.isInteger(column) && [ASC_SORT_STATE, DESC_SORT_STATE].includes(sortOrder);
}

/**
 * Get if all sorted columns states are valid.
 *
 * @param {Array} sortStates The sort state collection.
 * @returns {boolean}
 */
function areValidSortStates(sortStates) {
  if (sortStates.some(columnState => isValidColumnState(columnState) === false)) {
    return false;
  }
  const sortedColumns = sortStates.map(_ref => {
    let {
      column
    } = _ref;
    return column;
  });

  // Indexes occurs only once.
  return new Set(sortedColumns).size === sortedColumns.length;
}

/**
 * Get next sort order for particular column. The order sequence looks as follows: 'asc' -> 'desc' -> undefined -> 'asc'.
 *
 * @param {string|undefined} sortOrder Sort order (`asc` for ascending, `desc` for descending and undefined for not sorted).
 * @returns {string|undefined} Next sort order (`asc` for ascending, `desc` for descending and undefined for not sorted).
 */
function getNextSortOrder(sortOrder) {
  if (sortOrder === DESC_SORT_STATE) {
    return;
  } else if (sortOrder === ASC_SORT_STATE) {
    return DESC_SORT_STATE;
  }
  return ASC_SORT_STATE;
}

/**
 * Get `span` DOM element inside `th` DOM element.
 *
 * @param {Element} TH Th HTML element.
 * @returns {Element | null}
 */
function getHeaderSpanElement(TH) {
  const headerSpanElement = TH.querySelector(`.${HEADER_SPAN_CLASS}`);
  return headerSpanElement;
}

/**
 *
 * Get if handled header is first level column header.
 *
 * @param {number} column Visual column index.
 * @param {Element} TH Th HTML element.
 * @returns {boolean}
 */
function isFirstLevelColumnHeader(column, TH) {
  if (column < 0 || !TH.parentNode) {
    return false;
  }
  const TRs = TH.parentNode.parentNode.childNodes;
  const headerLevel = Array.from(TRs).indexOf(TH.parentNode) - TRs.length;
  if (headerLevel !== -1) {
    return false;
  }
  return true;
}

/**
 *  Get if header was clicked properly. Click on column header and NOT done by right click return `true`.
 *
 * @param {number} row Visual row index.
 * @param {number} column Visual column index.
 * @param {Event} clickEvent Click event.
 * @returns {boolean}
 */
function wasHeaderClickedProperly(row, column, clickEvent) {
  return row === -1 && column >= 0 && (0, _event.isRightClick)(clickEvent) === false;
}

/**
 * Creates date or time sorting compare function.
 *
 * @param {string} sortOrder Sort order (`asc` for ascending, `desc` for descending).
 * @param {string} format Date or time format.
 * @param {object} columnPluginSettings Plugin settings for the column.
 * @returns {Function} The compare function.
 */
function createDateTimeCompareFunction(sortOrder, format, columnPluginSettings) {
  return function (value, nextValue) {
    const {
      sortEmptyCells
    } = columnPluginSettings;
    if (value === nextValue) {
      return _sortService.DO_NOT_SWAP;
    }
    if ((0, _mixed.isEmpty)(value)) {
      if ((0, _mixed.isEmpty)(nextValue)) {
        return _sortService.DO_NOT_SWAP;
      }

      // Just fist value is empty and `sortEmptyCells` option was set
      if (sortEmptyCells) {
        return sortOrder === 'asc' ? _sortService.FIRST_BEFORE_SECOND : _sortService.FIRST_AFTER_SECOND;
      }
      return _sortService.FIRST_AFTER_SECOND;
    }
    if ((0, _mixed.isEmpty)(nextValue)) {
      // Just second value is empty and `sortEmptyCells` option was set
      if (sortEmptyCells) {
        return sortOrder === 'asc' ? _sortService.FIRST_AFTER_SECOND : _sortService.FIRST_BEFORE_SECOND;
      }
      return _sortService.FIRST_BEFORE_SECOND;
    }
    const firstDate = (0, _moment.default)(value, format);
    const nextDate = (0, _moment.default)(nextValue, format);
    if (!firstDate.isValid()) {
      return _sortService.FIRST_AFTER_SECOND;
    }
    if (!nextDate.isValid()) {
      return _sortService.FIRST_BEFORE_SECOND;
    }
    if (nextDate.isAfter(firstDate)) {
      return sortOrder === 'asc' ? _sortService.FIRST_BEFORE_SECOND : _sortService.FIRST_AFTER_SECOND;
    }
    if (nextDate.isBefore(firstDate)) {
      return sortOrder === 'asc' ? _sortService.FIRST_AFTER_SECOND : _sortService.FIRST_BEFORE_SECOND;
    }
    return _sortService.DO_NOT_SWAP;
  };
}