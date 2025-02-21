"use strict";

exports.__esModule = true;
exports.default = readOnlyItem;
var _utils = require("../utils");
var _array = require("../../../helpers/array");
var C = _interopRequireWildcard(require("../../../i18n/constants"));
function _getRequireWildcardCache(e) { if ("function" != typeof WeakMap) return null; var r = new WeakMap(), t = new WeakMap(); return (_getRequireWildcardCache = function (e) { return e ? t : r; })(e); }
function _interopRequireWildcard(e, r) { if (!r && e && e.__esModule) return e; if (null === e || "object" != typeof e && "function" != typeof e) return { default: e }; var t = _getRequireWildcardCache(r); if (t && t.has(e)) return t.get(e); var n = { __proto__: null }, a = Object.defineProperty && Object.getOwnPropertyDescriptor; for (var u in e) if ("default" !== u && {}.hasOwnProperty.call(e, u)) { var i = a ? Object.getOwnPropertyDescriptor(e, u) : null; i && (i.get || i.set) ? Object.defineProperty(n, u, i) : n[u] = e[u]; } return n.default = e, t && t.set(e, n), n; }
const KEY = exports.KEY = 'make_read_only';

/**
 * @returns {object}
 */
function readOnlyItem() {
  return {
    key: KEY,
    checkable: true,
    ariaChecked() {
      const atLeastOneReadOnly = (0, _utils.checkSelectionConsistency)(this.getSelectedRange(), (row, col) => this.getCellMeta(row, col).readOnly);
      return atLeastOneReadOnly;
    },
    ariaLabel() {
      return this.getTranslatedPhrase(C.CONTEXTMENU_ITEMS_READ_ONLY);
    },
    name() {
      let label = this.getTranslatedPhrase(C.CONTEXTMENU_ITEMS_READ_ONLY);
      const atLeastOneReadOnly = (0, _utils.checkSelectionConsistency)(this.getSelectedRange(), (row, col) => this.getCellMeta(row, col).readOnly);
      if (atLeastOneReadOnly) {
        label = (0, _utils.markLabelAsSelected)(label);
      }
      return label;
    },
    callback() {
      const ranges = this.getSelectedRange();
      const atLeastOneReadOnly = (0, _utils.checkSelectionConsistency)(ranges, (row, col) => this.getCellMeta(row, col).readOnly);
      (0, _array.arrayEach)(ranges, range => {
        range.forAll((row, col) => {
          if (row >= 0 && col >= 0) {
            this.setCellMeta(row, col, 'readOnly', !atLeastOneReadOnly);
          }
        });
      });
      this.render();
    },
    disabled() {
      const range = this.getSelectedRangeLast();
      if (!range) {
        return true;
      }
      if (range.isSingleHeader()) {
        return true;
      }
      if (this.selection.isSelectedByCorner()) {
        return true;
      }
      if (this.countRows() === 0 || this.countCols() === 0) {
        return true;
      }
      if (!this.getSelectedRange() || this.getSelectedRange().length === 0) {
        return true;
      }
      return false;
    }
  };
}