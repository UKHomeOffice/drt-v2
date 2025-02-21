"use strict";

exports.__esModule = true;
exports.getClassesToAdd = getClassesToAdd;
exports.getClassesToRemove = getClassesToRemove;
require("core-js/modules/es.array.push.js");
require("core-js/modules/esnext.iterator.constructor.js");
require("core-js/modules/esnext.iterator.filter.js");
const COLUMN_ORDER_PREFIX = 'sort';

/**
 * Get CSS classes which should be added to particular column header.
 *
 * @param {object} columnStatesManager Instance of column state manager.
 * @param {number} column Visual column index.
 * @param {boolean} showSortIndicator Indicates if indicator should be shown for the particular column.
 * @returns {Array} Array of CSS classes.
 */
function getClassesToAdd(columnStatesManager, column, showSortIndicator) {
  const cssClasses = [];
  if (showSortIndicator === false) {
    return cssClasses;
  }
  if (columnStatesManager.isColumnSorted(column) && columnStatesManager.getNumberOfSortedColumns() > 1) {
    cssClasses.push(`${COLUMN_ORDER_PREFIX}-${columnStatesManager.getIndexOfColumnInSortQueue(column) + 1}`);
  }
  return cssClasses;
}

/**
 * Get CSS classes which should be removed from column header.
 *
 * @param {HTMLElement} htmlElement An element to process.
 * @returns {Array} Array of CSS classes.
 */
function getClassesToRemove(htmlElement) {
  const cssClasses = htmlElement.className.split(' ');
  const sortSequenceRegExp = new RegExp(`^${COLUMN_ORDER_PREFIX}-[0-9]{1,2}$`);
  return cssClasses.filter(cssClass => sortSequenceRegExp.test(cssClass));
}