"use strict";

exports.__esModule = true;
exports.checkSelectionBorders = checkSelectionBorders;
exports.createDefaultCustomBorder = createDefaultCustomBorder;
exports.createDefaultHtBorder = createDefaultHtBorder;
exports.createEmptyBorders = createEmptyBorders;
exports.createId = createId;
exports.createSingleEmptyBorder = createSingleEmptyBorder;
exports.denormalizeBorder = denormalizeBorder;
exports.extendDefaultBorder = extendDefaultBorder;
exports.hasLeftRightTypeOptions = hasLeftRightTypeOptions;
exports.hasStartEndTypeOptions = hasStartEndTypeOptions;
exports.markSelected = markSelected;
exports.normalizeBorder = normalizeBorder;
exports.toInlinePropName = toInlinePropName;
require("core-js/modules/esnext.iterator.constructor.js");
require("core-js/modules/esnext.iterator.some.js");
var _object = require("../../helpers/object");
var _mixed = require("../../helpers/mixed");
var _array = require("../../helpers/array");
/**
 * Create separated id for borders for each cell.
 *
 * @param {number} row Visual row index.
 * @param {number} col Visual column index.
 * @returns {string}
 */
function createId(row, col) {
  return `border_row${row}col${col}`;
}

/**
 * Create default single border for each position (top/right/bottom/left).
 *
 * @returns {object} `{{width: number, color: string}}`.
 */
function createDefaultCustomBorder() {
  return {
    width: 1,
    color: '#000'
  };
}

/**
 * Create default object for empty border.
 *
 * @returns {object} `{{hide: boolean}}`.
 */
function createSingleEmptyBorder() {
  return {
    hide: true
  };
}

/**
 * Create default Handsontable border object.
 *
 * @returns {object} `{{width: number, color: string, cornerVisible: boolean}}`.
 */
function createDefaultHtBorder() {
  return {
    width: 1,
    color: '#000',
    cornerVisible: false
  };
}

/**
 * Normalizes the border object to be compatible with the Border API from the Walkontable.
 * The function translates the "left"/"right" properties to "start"/"end" prop names.
 *
 * @param {object} border The configuration object of the border.
 * @returns {object}
 */
function normalizeBorder(border) {
  if ((0, _mixed.isDefined)(border.start) || (0, _mixed.isDefined)(border.left)) {
    var _border$start;
    border.start = (_border$start = border.start) !== null && _border$start !== void 0 ? _border$start : border.left;
  }
  if ((0, _mixed.isDefined)(border.end) || (0, _mixed.isDefined)(border.right)) {
    var _border$end;
    border.end = (_border$end = border.end) !== null && _border$end !== void 0 ? _border$end : border.right;
  }
  delete border.left;
  delete border.right;
  return border;
}

/**
 * Denormalizes the border object to be backward compatible with the previous version of the CustomBorders
 * plugin API. The function extends the border configuration object for the backward compatible "left"/"right"
 * properties.
 *
 * @param {object} border The configuration object of the border.
 * @returns {object}
 */
function denormalizeBorder(border) {
  if ((0, _mixed.isDefined)(border.start)) {
    border.left = border.start;
  }
  if ((0, _mixed.isDefined)(border.end)) {
    border.right = border.end;
  }
  return border;
}

/**
 * Prepare empty border for each cell with all custom borders hidden.
 *
 * @param {number} row Visual row index.
 * @param {number} col Visual column index.
 * @returns {{id: string, border: any, row: number, col: number, top: {hide: boolean}, bottom: {hide: boolean}, start: {hide: boolean}, end: {hide: boolean}}} Returns border configuration containing visual indexes.
 */
function createEmptyBorders(row, col) {
  return {
    id: createId(row, col),
    border: createDefaultHtBorder(),
    row,
    col,
    top: createSingleEmptyBorder(),
    bottom: createSingleEmptyBorder(),
    start: createSingleEmptyBorder(),
    end: createSingleEmptyBorder()
  };
}

/**
 * @param {object} defaultBorder The default border object.
 * @param {object} customBorder The border object with custom settings.
 * @returns {object}
 */
function extendDefaultBorder(defaultBorder, customBorder) {
  if ((0, _object.hasOwnProperty)(customBorder, 'border') && customBorder.border) {
    defaultBorder.border = customBorder.border;
  }
  if ((0, _object.hasOwnProperty)(customBorder, 'top') && (0, _mixed.isDefined)(customBorder.top)) {
    if (customBorder.top) {
      if (!(0, _object.isObject)(customBorder.top)) {
        customBorder.top = createDefaultCustomBorder();
      }
      defaultBorder.top = customBorder.top;
    } else {
      customBorder.top = createSingleEmptyBorder();
      defaultBorder.top = customBorder.top;
    }
  }
  if ((0, _object.hasOwnProperty)(customBorder, 'bottom') && (0, _mixed.isDefined)(customBorder.bottom)) {
    if (customBorder.bottom) {
      if (!(0, _object.isObject)(customBorder.bottom)) {
        customBorder.bottom = createDefaultCustomBorder();
      }
      defaultBorder.bottom = customBorder.bottom;
    } else {
      customBorder.bottom = createSingleEmptyBorder();
      defaultBorder.bottom = customBorder.bottom;
    }
  }
  if ((0, _object.hasOwnProperty)(customBorder, 'start') && (0, _mixed.isDefined)(customBorder.start)) {
    if (customBorder.start) {
      if (!(0, _object.isObject)(customBorder.start)) {
        customBorder.start = createDefaultCustomBorder();
      }
      defaultBorder.start = customBorder.start;
    } else {
      customBorder.start = createSingleEmptyBorder();
      defaultBorder.start = customBorder.start;
    }
  }
  if ((0, _object.hasOwnProperty)(customBorder, 'end') && (0, _mixed.isDefined)(customBorder.end)) {
    if (customBorder.end) {
      if (!(0, _object.isObject)(customBorder.end)) {
        customBorder.end = createDefaultCustomBorder();
      }
      defaultBorder.end = customBorder.end;
    } else {
      customBorder.end = createSingleEmptyBorder();
      defaultBorder.end = customBorder.end;
    }
  }
  return defaultBorder;
}

/**
 * Check if selection has border.
 *
 * @param {Core} hot The Handsontable instance.
 * @param {string} [direction] If set ('left' or 'top') then only the specified border side will be checked.
 * @returns {boolean}
 */
function checkSelectionBorders(hot, direction) {
  let atLeastOneHasBorder = false;
  (0, _array.arrayEach)(hot.getSelectedRange(), range => {
    range.forAll((r, c) => {
      if (r < 0 || c < 0) {
        return;
      }
      const metaBorders = hot.getCellMeta(r, c).borders;
      if (metaBorders) {
        if (direction) {
          if (!(0, _object.hasOwnProperty)(metaBorders[direction], 'hide') || metaBorders[direction].hide === false) {
            atLeastOneHasBorder = true;
            return false; // breaks forAll
          }
        } else {
          atLeastOneHasBorder = true;
          return false; // breaks forAll
        }
      }
    });
  });
  return atLeastOneHasBorder;
}

/**
 * Mark label in contextMenu as selected.
 *
 * @param {string} label The label text.
 * @returns {string}
 */
function markSelected(label) {
  return `<span class="selected">${String.fromCharCode(10003)}</span>${label}`; // workaround for https://github.com/handsontable/handsontable/issues/1946
}

/**
 * Checks if in the borders config there are defined "left" or "right" border properties.
 *
 * @param {object[]} borders The custom border plugin's options.
 * @returns {boolean}
 */
function hasLeftRightTypeOptions(borders) {
  return borders.some(border => (0, _mixed.isDefined)(border.left) || (0, _mixed.isDefined)(border.right));
}

/**
 * Checks if in the borders config there are defined "start" or "end" border properties.
 *
 * @param {object[]} borders The custom border plugin's options.
 * @returns {boolean}
 */
function hasStartEndTypeOptions(borders) {
  return borders.some(border => (0, _mixed.isDefined)(border.start) || (0, _mixed.isDefined)(border.end));
}
const physicalToInlinePropNames = new Map([['left', 'start'], ['right', 'end']]);

/**
 * Translates the physical horizontal direction to logical ones. If not known property name is
 * passed it will be returned without modification.
 *
 * @param {string} propName The physical direction property name ("left" or "right").
 * @returns {string}
 */
function toInlinePropName(propName) {
  var _physicalToInlineProp;
  return (_physicalToInlineProp = physicalToInlinePropNames.get(propName)) !== null && _physicalToInlineProp !== void 0 ? _physicalToInlineProp : propName;
}