"use strict";

exports.__esModule = true;
exports.default = hideColumnItem;
require("core-js/modules/es.array.push.js");
var C = _interopRequireWildcard(require("../../../i18n/constants"));
function _getRequireWildcardCache(e) { if ("function" != typeof WeakMap) return null; var r = new WeakMap(), t = new WeakMap(); return (_getRequireWildcardCache = function (e) { return e ? t : r; })(e); }
function _interopRequireWildcard(e, r) { if (!r && e && e.__esModule) return e; if (null === e || "object" != typeof e && "function" != typeof e) return { default: e }; var t = _getRequireWildcardCache(r); if (t && t.has(e)) return t.get(e); var n = { __proto__: null }, a = Object.defineProperty && Object.getOwnPropertyDescriptor; for (var u in e) if ("default" !== u && {}.hasOwnProperty.call(e, u)) { var i = a ? Object.getOwnPropertyDescriptor(e, u) : null; i && (i.get || i.set) ? Object.defineProperty(n, u, i) : n[u] = e[u]; } return n.default = e, t && t.set(e, n), n; }
/**
 * @param {HiddenColumns} hiddenColumnsPlugin The plugin instance.
 * @returns {object}
 */
function hideColumnItem(hiddenColumnsPlugin) {
  return {
    key: 'hidden_columns_hide',
    name() {
      const selection = this.getSelectedLast();
      let pluralForm = 0;
      if (Array.isArray(selection)) {
        const [, fromColumn,, toColumn] = selection;
        if (fromColumn - toColumn !== 0) {
          pluralForm = 1;
        }
      }
      return this.getTranslatedPhrase(C.CONTEXTMENU_ITEMS_HIDE_COLUMN, pluralForm);
    },
    callback() {
      const {
        from,
        to
      } = this.getSelectedRangeLast();
      const start = Math.max(Math.min(from.col, to.col), 0);
      const end = Math.max(from.col, to.col);
      const columnsToHide = [];
      for (let visualColumn = start; visualColumn <= end; visualColumn += 1) {
        columnsToHide.push(visualColumn);
      }
      hiddenColumnsPlugin.hideColumns(columnsToHide);
      const lastHiddenColumn = columnsToHide[columnsToHide.length - 1];
      const columnToSelect = this.columnIndexMapper.getNearestNotHiddenIndex(lastHiddenColumn, 1, true);
      if (Number.isInteger(columnToSelect) && columnToSelect >= 0) {
        this.selectColumns(columnToSelect);
      } else {
        this.deselectCell();
      }
      this.render();
      this.view.adjustElementsSize();
    },
    disabled: false,
    hidden() {
      return !(this.selection.isSelectedByColumnHeader() || this.selection.isSelectedByCorner());
    }
  };
}