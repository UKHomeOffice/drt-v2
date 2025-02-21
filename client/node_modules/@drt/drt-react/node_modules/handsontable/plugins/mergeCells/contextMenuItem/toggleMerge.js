"use strict";

exports.__esModule = true;
exports.default = toggleMergeItem;
var C = _interopRequireWildcard(require("../../../i18n/constants"));
var _cellCoords = _interopRequireDefault(require("../cellCoords"));
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
function _getRequireWildcardCache(e) { if ("function" != typeof WeakMap) return null; var r = new WeakMap(), t = new WeakMap(); return (_getRequireWildcardCache = function (e) { return e ? t : r; })(e); }
function _interopRequireWildcard(e, r) { if (!r && e && e.__esModule) return e; if (null === e || "object" != typeof e && "function" != typeof e) return { default: e }; var t = _getRequireWildcardCache(r); if (t && t.has(e)) return t.get(e); var n = { __proto__: null }, a = Object.defineProperty && Object.getOwnPropertyDescriptor; for (var u in e) if ("default" !== u && {}.hasOwnProperty.call(e, u)) { var i = a ? Object.getOwnPropertyDescriptor(e, u) : null; i && (i.get || i.set) ? Object.defineProperty(n, u, i) : n[u] = e[u]; } return n.default = e, t && t.set(e, n), n; }
/**
 * @param {*} plugin The plugin instance.
 * @returns {object}
 */
function toggleMergeItem(plugin) {
  return {
    key: 'mergeCells',
    name() {
      const sel = this.getSelectedLast();
      if (sel) {
        const info = plugin.mergedCellsCollection.get(sel[0], sel[1]);
        if (info.row === sel[0] && info.col === sel[1] && info.row + info.rowspan - 1 === sel[2] && info.col + info.colspan - 1 === sel[3]) {
          return this.getTranslatedPhrase(C.CONTEXTMENU_ITEMS_UNMERGE_CELLS);
        }
      }
      return this.getTranslatedPhrase(C.CONTEXTMENU_ITEMS_MERGE_CELLS);
    },
    callback() {
      const currentRange = this.getSelectedRangeLast();
      if (!currentRange) {
        return;
      }
      currentRange.setDirection(this.isRtl() ? 'NE-SW' : 'NW-SE');
      const {
        from,
        to
      } = currentRange;
      plugin.toggleMerge(currentRange);
      this.selectCell(from.row, from.col, to.row, to.col, false);
    },
    disabled() {
      const sel = this.getSelectedLast();
      if (!sel) {
        return true;
      }
      const isSingleCell = _cellCoords.default.isSingleCell({
        row: sel[0],
        col: sel[1],
        rowspan: sel[2] - sel[0] + 1,
        colspan: sel[3] - sel[1] + 1
      });
      return isSingleCell || this.selection.isSelectedByCorner();
    },
    hidden: false
  };
}