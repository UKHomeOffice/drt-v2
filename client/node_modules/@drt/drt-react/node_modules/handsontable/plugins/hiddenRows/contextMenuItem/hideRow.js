"use strict";

exports.__esModule = true;
exports.default = hideRowItem;
require("core-js/modules/es.array.push.js");
var C = _interopRequireWildcard(require("../../../i18n/constants"));
function _getRequireWildcardCache(e) { if ("function" != typeof WeakMap) return null; var r = new WeakMap(), t = new WeakMap(); return (_getRequireWildcardCache = function (e) { return e ? t : r; })(e); }
function _interopRequireWildcard(e, r) { if (!r && e && e.__esModule) return e; if (null === e || "object" != typeof e && "function" != typeof e) return { default: e }; var t = _getRequireWildcardCache(r); if (t && t.has(e)) return t.get(e); var n = { __proto__: null }, a = Object.defineProperty && Object.getOwnPropertyDescriptor; for (var u in e) if ("default" !== u && {}.hasOwnProperty.call(e, u)) { var i = a ? Object.getOwnPropertyDescriptor(e, u) : null; i && (i.get || i.set) ? Object.defineProperty(n, u, i) : n[u] = e[u]; } return n.default = e, t && t.set(e, n), n; }
/**
 * @param {HiddenRows} hiddenRowsPlugin The plugin instance.
 * @returns {object}
 */
function hideRowItem(hiddenRowsPlugin) {
  return {
    key: 'hidden_rows_hide',
    name() {
      const selection = this.getSelectedLast();
      let pluralForm = 0;
      if (Array.isArray(selection)) {
        const [fromRow,, toRow] = selection;
        if (fromRow - toRow !== 0) {
          pluralForm = 1;
        }
      }
      return this.getTranslatedPhrase(C.CONTEXTMENU_ITEMS_HIDE_ROW, pluralForm);
    },
    callback() {
      const {
        from,
        to
      } = this.getSelectedRangeLast();
      const start = Math.max(Math.min(from.row, to.row), 0);
      const end = Math.max(from.row, to.row);
      const rowsToHide = [];
      for (let visualRow = start; visualRow <= end; visualRow += 1) {
        rowsToHide.push(visualRow);
      }
      hiddenRowsPlugin.hideRows(rowsToHide);
      const lastHiddenRow = rowsToHide[rowsToHide.length - 1];
      const rowToSelect = this.rowIndexMapper.getNearestNotHiddenIndex(lastHiddenRow, 1, true);
      if (Number.isInteger(rowToSelect) && rowToSelect >= 0) {
        this.selectRows(rowToSelect);
      } else {
        this.deselectCell();
      }
      this.render();
      this.view.adjustElementsSize();
    },
    disabled: false,
    hidden() {
      return !(this.selection.isSelectedByRowHeader() || this.selection.isSelectedByCorner());
    }
  };
}