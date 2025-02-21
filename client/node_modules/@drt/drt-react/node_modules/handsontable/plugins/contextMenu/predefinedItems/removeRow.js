"use strict";

exports.__esModule = true;
exports.default = removeRowItem;
var _utils = require("../../../selection/utils");
var C = _interopRequireWildcard(require("../../../i18n/constants"));
function _getRequireWildcardCache(e) { if ("function" != typeof WeakMap) return null; var r = new WeakMap(), t = new WeakMap(); return (_getRequireWildcardCache = function (e) { return e ? t : r; })(e); }
function _interopRequireWildcard(e, r) { if (!r && e && e.__esModule) return e; if (null === e || "object" != typeof e && "function" != typeof e) return { default: e }; var t = _getRequireWildcardCache(r); if (t && t.has(e)) return t.get(e); var n = { __proto__: null }, a = Object.defineProperty && Object.getOwnPropertyDescriptor; for (var u in e) if ("default" !== u && {}.hasOwnProperty.call(e, u)) { var i = a ? Object.getOwnPropertyDescriptor(e, u) : null; i && (i.get || i.set) ? Object.defineProperty(n, u, i) : n[u] = e[u]; } return n.default = e, t && t.set(e, n), n; }
const KEY = exports.KEY = 'remove_row';

/**
 * @returns {object}
 */
function removeRowItem() {
  return {
    key: KEY,
    name() {
      const selection = this.getSelected();
      let pluralForm = 0;
      if (selection) {
        if (selection.length > 1) {
          pluralForm = 1;
        } else {
          const [fromRow,, toRow] = selection[0];
          if (fromRow - toRow !== 0) {
            pluralForm = 1;
          }
        }
      }
      return this.getTranslatedPhrase(C.CONTEXTMENU_ITEMS_REMOVE_ROW, pluralForm);
    },
    callback() {
      // TODO: Please keep in mind that below `1` may be improper. The table's way of work, before change `f1747b3912ea3b21fe423fd102ca94c87db81379` was restored.
      // There is still problem when removing more than one row.
      this.alter('remove_row', (0, _utils.transformSelectionToRowDistance)(this), 1, 'ContextMenu.removeRow');
    },
    disabled() {
      const range = this.getSelectedRangeLast();
      if (!range) {
        return true;
      }
      if (range.isSingleHeader() && range.highlight.row < 0) {
        return true;
      }
      const totalRows = this.countRows();
      if (this.selection.isSelectedByCorner()) {
        // Enable "Remove row" only when there is at least one row.
        return totalRows === 0;
      }
      return this.selection.isSelectedByColumnHeader() || totalRows === 0;
    },
    hidden() {
      return !this.getSettings().allowRemoveRow;
    }
  };
}