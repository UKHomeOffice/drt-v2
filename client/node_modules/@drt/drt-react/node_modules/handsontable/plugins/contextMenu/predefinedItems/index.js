"use strict";

exports.__esModule = true;
exports.addItem = addItem;
exports.predefinedItems = predefinedItems;
var _object = require("../../../helpers/object");
var _alignment = _interopRequireWildcard(require("./alignment"));
exports.ALIGNMENT = _alignment.KEY;
var _clearColumn = _interopRequireWildcard(require("./clearColumn"));
exports.CLEAR_COLUMN = _clearColumn.KEY;
var _columnLeft = _interopRequireWildcard(require("./columnLeft"));
exports.COLUMN_LEFT = _columnLeft.KEY;
var _columnRight = _interopRequireWildcard(require("./columnRight"));
exports.COLUMN_RIGHT = _columnRight.KEY;
var _readOnly = _interopRequireWildcard(require("./readOnly"));
exports.READ_ONLY = _readOnly.KEY;
var _redo = _interopRequireWildcard(require("./redo"));
exports.REDO = _redo.KEY;
var _removeColumn = _interopRequireWildcard(require("./removeColumn"));
exports.REMOVE_COLUMN = _removeColumn.KEY;
var _removeRow = _interopRequireWildcard(require("./removeRow"));
exports.REMOVE_ROW = _removeRow.KEY;
var _rowAbove = _interopRequireWildcard(require("./rowAbove"));
exports.ROW_ABOVE = _rowAbove.KEY;
var _rowBelow = _interopRequireWildcard(require("./rowBelow"));
exports.ROW_BELOW = _rowBelow.KEY;
var _separator = _interopRequireWildcard(require("./separator"));
exports.SEPARATOR = _separator.KEY;
var _noItems = _interopRequireWildcard(require("./noItems"));
exports.NO_ITEMS = _noItems.KEY;
var _undo = _interopRequireWildcard(require("./undo"));
exports.UNDO = _undo.KEY;
function _getRequireWildcardCache(e) { if ("function" != typeof WeakMap) return null; var r = new WeakMap(), t = new WeakMap(); return (_getRequireWildcardCache = function (e) { return e ? t : r; })(e); }
function _interopRequireWildcard(e, r) { if (!r && e && e.__esModule) return e; if (null === e || "object" != typeof e && "function" != typeof e) return { default: e }; var t = _getRequireWildcardCache(r); if (t && t.has(e)) return t.get(e); var n = { __proto__: null }, a = Object.defineProperty && Object.getOwnPropertyDescriptor; for (var u in e) if ("default" !== u && {}.hasOwnProperty.call(e, u)) { var i = a ? Object.getOwnPropertyDescriptor(e, u) : null; i && (i.get || i.set) ? Object.defineProperty(n, u, i) : n[u] = e[u]; } return n.default = e, t && t.set(e, n), n; }
const ITEMS = exports.ITEMS = [_rowAbove.KEY, _rowBelow.KEY, _columnLeft.KEY, _columnRight.KEY, _clearColumn.KEY, _removeRow.KEY, _removeColumn.KEY, _undo.KEY, _redo.KEY, _readOnly.KEY, _alignment.KEY, _separator.KEY, _noItems.KEY];
const _predefinedItems = {
  [_separator.KEY]: _separator.default,
  [_noItems.KEY]: _noItems.default,
  [_rowAbove.KEY]: _rowAbove.default,
  [_rowBelow.KEY]: _rowBelow.default,
  [_columnLeft.KEY]: _columnLeft.default,
  [_columnRight.KEY]: _columnRight.default,
  [_clearColumn.KEY]: _clearColumn.default,
  [_removeRow.KEY]: _removeRow.default,
  [_removeColumn.KEY]: _removeColumn.default,
  [_undo.KEY]: _undo.default,
  [_redo.KEY]: _redo.default,
  [_readOnly.KEY]: _readOnly.default,
  [_alignment.KEY]: _alignment.default
};

/**
 * Gets new object with all predefined menu items.
 *
 * @returns {object}
 */
function predefinedItems() {
  const items = {};
  (0, _object.objectEach)(_predefinedItems, (itemFactory, key) => {
    items[key] = itemFactory();
  });
  return items;
}

/**
 * Add new predefined menu item to the collection.
 *
 * @param {string} key Menu command id.
 * @param {object} item Object command descriptor.
 */
function addItem(key, item) {
  if (ITEMS.indexOf(key) === -1) {
    _predefinedItems[key] = item;
  }
}