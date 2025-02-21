"use strict";

exports.__esModule = true;
exports.filterSeparators = filterSeparators;
exports.hasSubMenu = hasSubMenu;
exports.isDisabled = isDisabled;
exports.isItemCheckable = isItemCheckable;
exports.isItemDisabled = isItemDisabled;
exports.isItemHidden = isItemHidden;
exports.isItemSelectionDisabled = isItemSelectionDisabled;
exports.isItemSeparator = isItemSeparator;
exports.isItemSubMenu = isItemSubMenu;
exports.isSelectionDisabled = isSelectionDisabled;
exports.isSeparator = isSeparator;
exports.normalizeSelection = normalizeSelection;
require("core-js/modules/es.array.push.js");
var _array = require("../../../helpers/array");
var _object = require("../../../helpers/object");
var _element = require("../../../helpers/dom/element");
var _predefinedItems = require("./../predefinedItems");
/**
 * @param {CellRange[]} selRanges An array of the cell ranges.
 * @returns {object[]}
 */
function normalizeSelection(selRanges) {
  return (0, _array.arrayMap)(selRanges, range => ({
    start: range.getTopStartCorner(),
    end: range.getBottomEndCorner()
  }));
}

/**
 * Check if the provided element is a submenu opener.
 *
 * @param {object} itemToTest Item element.
 * @returns {boolean}
 */
function isItemSubMenu(itemToTest) {
  return (0, _object.hasOwnProperty)(itemToTest, 'submenu');
}

/**
 * Check if the provided element is a menu separator.
 *
 * @param {object} itemToTest Item element.
 * @returns {boolean}
 */
function isItemSeparator(itemToTest) {
  return new RegExp(_predefinedItems.SEPARATOR, 'i').test(itemToTest.name);
}

/**
 * Check if the provided element presents the disabled menu item.
 *
 * @param {object} itemToTest Item element.
 * @param {object} hot The context for the item function.
 * @returns {boolean}
 */
function isItemDisabled(itemToTest, hot) {
  return itemToTest.disabled === true || typeof itemToTest.disabled === 'function' && itemToTest.disabled.call(hot) === true;
}

/**
 * Check if the provided element presents the disabled selection menu item.
 *
 * @param {object} itemToTest Item element.
 * @returns {boolean}
 */
function isItemSelectionDisabled(itemToTest) {
  return (0, _object.hasOwnProperty)(itemToTest, 'disableSelection');
}

/**
 * @param {HTMLElement} cell The HTML cell element to check.
 * @returns {boolean}
 */
function isSeparator(cell) {
  return (0, _element.hasClass)(cell, 'htSeparator');
}

/**
 * @param {HTMLElement} cell The HTML cell element to check.
 * @returns {boolean}
 */
function hasSubMenu(cell) {
  return (0, _element.hasClass)(cell, 'htSubmenu');
}

/**
 * @param {HTMLElement} cell The HTML cell element to check.
 * @returns {boolean}
 */
function isDisabled(cell) {
  return (0, _element.hasClass)(cell, 'htDisabled');
}

/**
 * @param {HTMLElement} cell The HTML cell element to check.
 * @returns {boolean}
 */
function isSelectionDisabled(cell) {
  return (0, _element.hasClass)(cell, 'htSelectionDisabled');
}

/**
 * @param {object} item The object which describes the context menu item properties.
 * @param {Core} instance The Handsontable instance.
 * @returns {boolean}
 */
function isItemHidden(item, instance) {
  return !item.hidden || !(typeof item.hidden === 'function' && item.hidden.call(instance));
}

/**
 * @param {object[]} items The context menu items collection.
 * @param {string} separator The string which identifies the context menu separator item.
 * @returns {object[]}
 */
function shiftSeparators(items, separator) {
  const result = items.slice(0);
  for (let i = 0; i < result.length;) {
    if (result[i].name === separator) {
      result.shift();
    } else {
      break;
    }
  }
  return result;
}

/**
 * @param {object[]} items The context menu items collection.
 * @param {string} separator The string which identifies the context menu separator item.
 * @returns {object[]}
 */
function popSeparators(items, separator) {
  let result = items.slice(0);
  result.reverse();
  result = shiftSeparators(result, separator);
  result.reverse();
  return result;
}

/**
 * Removes duplicated menu separators from the context menu items collection.
 *
 * @param {object[]} items The context menu items collection.
 * @returns {object[]}
 */
function removeDuplicatedSeparators(items) {
  const result = [];
  (0, _array.arrayEach)(items, (value, index) => {
    if (index > 0) {
      if (result[result.length - 1].name !== value.name) {
        result.push(value);
      }
    } else {
      result.push(value);
    }
  });
  return result;
}

/**
 * Removes menu separators from the context menu items collection.
 *
 * @param {object[]} items The context menu items collection.
 * @param {string} separator The string which identifies the context menu separator item.
 * @returns {object[]}
 */
function filterSeparators(items) {
  let separator = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : _predefinedItems.SEPARATOR;
  let result = items.slice(0);
  result = shiftSeparators(result, separator);
  result = popSeparators(result, separator);
  result = removeDuplicatedSeparators(result);
  return result;
}

/**
 * Check if the provided element presents the checkboxable menu item.
 *
 * @param {object} itemToTest Item element.
 * @returns {boolean}
 */
function isItemCheckable(itemToTest) {
  return itemToTest.checkable === true;
}