"use strict";

exports.__esModule = true;
exports.default = removeColumnItem;
var _utils = require("../../../selection/utils");
var C = _interopRequireWildcard(require("../../../i18n/constants"));
function _getRequireWildcardCache(e) { if ("function" != typeof WeakMap) return null; var r = new WeakMap(), t = new WeakMap(); return (_getRequireWildcardCache = function (e) { return e ? t : r; })(e); }
function _interopRequireWildcard(e, r) { if (!r && e && e.__esModule) return e; if (null === e || "object" != typeof e && "function" != typeof e) return { default: e }; var t = _getRequireWildcardCache(r); if (t && t.has(e)) return t.get(e); var n = { __proto__: null }, a = Object.defineProperty && Object.getOwnPropertyDescriptor; for (var u in e) if ("default" !== u && {}.hasOwnProperty.call(e, u)) { var i = a ? Object.getOwnPropertyDescriptor(e, u) : null; i && (i.get || i.set) ? Object.defineProperty(n, u, i) : n[u] = e[u]; } return n.default = e, t && t.set(e, n), n; }
const KEY = exports.KEY = 'remove_col';

/**
 * @returns {object}
 */
function removeColumnItem() {
  return {
    key: KEY,
    name() {
      const selection = this.getSelected();
      let pluralForm = 0;
      if (selection) {
        if (selection.length > 1) {
          pluralForm = 1;
        } else {
          const [, fromColumn,, toColumn] = selection[0];
          if (fromColumn - toColumn !== 0) {
            pluralForm = 1;
          }
        }
      }
      return this.getTranslatedPhrase(C.CONTEXTMENU_ITEMS_REMOVE_COLUMN, pluralForm);
    },
    callback() {
      this.alter('remove_col', (0, _utils.transformSelectionToColumnDistance)(this), null, 'ContextMenu.removeColumn');
    },
    disabled() {
      if (!this.isColumnModificationAllowed()) {
        return true;
      }
      const range = this.getSelectedRangeLast();
      if (!range) {
        return true;
      }
      if (range.isSingleHeader() && range.highlight.col < 0) {
        return true;
      }
      const totalColumns = this.countCols();
      if (this.selection.isSelectedByCorner()) {
        // Enable "Remove column" only when there is at least one column.
        return totalColumns === 0;
      }
      return this.selection.isSelectedByRowHeader() || totalColumns === 0;
    },
    hidden() {
      return !this.getSettings().allowRemoveColumn;
    }
  };
}