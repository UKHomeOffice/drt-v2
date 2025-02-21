"use strict";

exports.__esModule = true;
exports.default = clearColumnItem;
var C = _interopRequireWildcard(require("../../../i18n/constants"));
function _getRequireWildcardCache(e) { if ("function" != typeof WeakMap) return null; var r = new WeakMap(), t = new WeakMap(); return (_getRequireWildcardCache = function (e) { return e ? t : r; })(e); }
function _interopRequireWildcard(e, r) { if (!r && e && e.__esModule) return e; if (null === e || "object" != typeof e && "function" != typeof e) return { default: e }; var t = _getRequireWildcardCache(r); if (t && t.has(e)) return t.get(e); var n = { __proto__: null }, a = Object.defineProperty && Object.getOwnPropertyDescriptor; for (var u in e) if ("default" !== u && {}.hasOwnProperty.call(e, u)) { var i = a ? Object.getOwnPropertyDescriptor(e, u) : null; i && (i.get || i.set) ? Object.defineProperty(n, u, i) : n[u] = e[u]; } return n.default = e, t && t.set(e, n), n; }
const KEY = exports.KEY = 'clear_column';

/**
 * @returns {object}
 */
function clearColumnItem() {
  return {
    key: KEY,
    name() {
      return this.getTranslatedPhrase(C.CONTEXTMENU_ITEMS_CLEAR_COLUMN);
    },
    callback(key, selection) {
      const startColumn = selection[0].start.col;
      const endColumn = selection[0].end.col;
      if (this.countRows()) {
        this.populateFromArray(0, startColumn, [[null]], Math.max(selection[0].start.row, selection[0].end.row), endColumn, 'ContextMenu.clearColumn');
      }
    },
    disabled() {
      const range = this.getSelectedRangeLast();
      if (!range || range.isSingleHeader() && range.highlight.col < 0 || !this.selection.isSelectedByColumnHeader()) {
        return true;
      }
      let atLeastOneNonReadOnly = false;
      range.forAll((row, col) => {
        if (row < 0 || col < 0) {
          return true;
        }
        const {
          readOnly
        } = this.getCellMeta(row, col);
        if (!readOnly) {
          atLeastOneNonReadOnly = true;
          return false;
        }
        return true;
      });
      return !atLeastOneNonReadOnly;
    }
  };
}