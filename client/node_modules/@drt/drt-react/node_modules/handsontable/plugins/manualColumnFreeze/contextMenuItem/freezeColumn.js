"use strict";

exports.__esModule = true;
exports.default = freezeColumnItem;
var C = _interopRequireWildcard(require("../../../i18n/constants"));
function _getRequireWildcardCache(e) { if ("function" != typeof WeakMap) return null; var r = new WeakMap(), t = new WeakMap(); return (_getRequireWildcardCache = function (e) { return e ? t : r; })(e); }
function _interopRequireWildcard(e, r) { if (!r && e && e.__esModule) return e; if (null === e || "object" != typeof e && "function" != typeof e) return { default: e }; var t = _getRequireWildcardCache(r); if (t && t.has(e)) return t.get(e); var n = { __proto__: null }, a = Object.defineProperty && Object.getOwnPropertyDescriptor; for (var u in e) if ("default" !== u && {}.hasOwnProperty.call(e, u)) { var i = a ? Object.getOwnPropertyDescriptor(e, u) : null; i && (i.get || i.set) ? Object.defineProperty(n, u, i) : n[u] = e[u]; } return n.default = e, t && t.set(e, n), n; }
/**
 * @param {ManualColumnFreeze} manualColumnFreezePlugin The plugin instance.
 * @returns {object}
 */
function freezeColumnItem(manualColumnFreezePlugin) {
  return {
    key: 'freeze_column',
    name() {
      return this.getTranslatedPhrase(C.CONTEXTMENU_ITEMS_FREEZE_COLUMN);
    },
    callback(key, selected) {
      const [{
        start: {
          col: selectedColumn
        }
      }] = selected;
      manualColumnFreezePlugin.freezeColumn(selectedColumn);
      this.render();
      this.view.adjustElementsSize();
    },
    hidden() {
      const selection = this.getSelectedRange();
      let hide = false;
      if (selection === undefined) {
        hide = true;
      } else if (selection.length > 1) {
        hide = true;
      } else if (selection[0].from.col !== selection[0].to.col || selection[0].from.col <= this.getSettings().fixedColumnsStart - 1) {
        hide = true;
      }
      return hide;
    }
  };
}