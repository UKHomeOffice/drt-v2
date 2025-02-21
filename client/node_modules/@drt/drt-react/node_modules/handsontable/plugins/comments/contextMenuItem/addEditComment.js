"use strict";

exports.__esModule = true;
exports.default = addEditCommentItem;
var C = _interopRequireWildcard(require("../../../i18n/constants"));
function _getRequireWildcardCache(e) { if ("function" != typeof WeakMap) return null; var r = new WeakMap(), t = new WeakMap(); return (_getRequireWildcardCache = function (e) { return e ? t : r; })(e); }
function _interopRequireWildcard(e, r) { if (!r && e && e.__esModule) return e; if (null === e || "object" != typeof e && "function" != typeof e) return { default: e }; var t = _getRequireWildcardCache(r); if (t && t.has(e)) return t.get(e); var n = { __proto__: null }, a = Object.defineProperty && Object.getOwnPropertyDescriptor; for (var u in e) if ("default" !== u && {}.hasOwnProperty.call(e, u)) { var i = a ? Object.getOwnPropertyDescriptor(e, u) : null; i && (i.get || i.set) ? Object.defineProperty(n, u, i) : n[u] = e[u]; } return n.default = e, t && t.set(e, n), n; }
/**
 * @param {Comments} plugin The Comments plugin instance.
 * @returns {object}
 */
function addEditCommentItem(plugin) {
  return {
    key: 'commentsAddEdit',
    name() {
      var _this$getSelectedRang;
      const highlight = (_this$getSelectedRang = this.getSelectedRangeLast()) === null || _this$getSelectedRang === void 0 ? void 0 : _this$getSelectedRang.highlight;
      if (highlight !== null && highlight !== void 0 && highlight.isCell() && plugin.getCommentAtCell(highlight.row, highlight.col)) {
        return this.getTranslatedPhrase(C.CONTEXTMENU_ITEMS_EDIT_COMMENT);
      }
      return this.getTranslatedPhrase(C.CONTEXTMENU_ITEMS_ADD_COMMENT);
    },
    callback() {
      const range = this.getSelectedRangeLast();
      plugin.setRange(range);
      plugin.show();
      plugin.focusEditor();
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