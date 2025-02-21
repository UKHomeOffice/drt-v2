"use strict";

exports.__esModule = true;
exports.default = removeCommentItem;
var C = _interopRequireWildcard(require("../../../i18n/constants"));
function _getRequireWildcardCache(e) { if ("function" != typeof WeakMap) return null; var r = new WeakMap(), t = new WeakMap(); return (_getRequireWildcardCache = function (e) { return e ? t : r; })(e); }
function _interopRequireWildcard(e, r) { if (!r && e && e.__esModule) return e; if (null === e || "object" != typeof e && "function" != typeof e) return { default: e }; var t = _getRequireWildcardCache(r); if (t && t.has(e)) return t.get(e); var n = { __proto__: null }, a = Object.defineProperty && Object.getOwnPropertyDescriptor; for (var u in e) if ("default" !== u && {}.hasOwnProperty.call(e, u)) { var i = a ? Object.getOwnPropertyDescriptor(e, u) : null; i && (i.get || i.set) ? Object.defineProperty(n, u, i) : n[u] = e[u]; } return n.default = e, t && t.set(e, n), n; }
/**
 * @param {Comments} plugin The Comments plugin instance.
 * @returns {object}
 */
function removeCommentItem(plugin) {
  return {
    key: 'commentsRemove',
    name() {
      return this.getTranslatedPhrase(C.CONTEXTMENU_ITEMS_REMOVE_COMMENT);
    },
    callback() {
      const range = this.getSelectedRangeLast();
      range.forAll((row, column) => {
        if (row >= 0 && column >= 0) {
          plugin.removeCommentAtCell(row, column, false);
        }
      });
      this.render();
    },
    disabled() {
      const range = this.getSelectedRangeLast();
      if (!range || range.highlight.isHeader() || this.selection.isEntireRowSelected() && this.selection.isEntireColumnSelected() || this.countRenderedRows() === 0 || this.countRenderedCols() === 0) {
        return true;
      }
      return false;
    }
  };
}