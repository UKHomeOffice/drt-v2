"use strict";

exports.__esModule = true;
exports.default = right;
var C = _interopRequireWildcard(require("../../../i18n/constants"));
var _utils = require("../utils");
function _getRequireWildcardCache(e) { if ("function" != typeof WeakMap) return null; var r = new WeakMap(), t = new WeakMap(); return (_getRequireWildcardCache = function (e) { return e ? t : r; })(e); }
function _interopRequireWildcard(e, r) { if (!r && e && e.__esModule) return e; if (null === e || "object" != typeof e && "function" != typeof e) return { default: e }; var t = _getRequireWildcardCache(r); if (t && t.has(e)) return t.get(e); var n = { __proto__: null }, a = Object.defineProperty && Object.getOwnPropertyDescriptor; for (var u in e) if ("default" !== u && {}.hasOwnProperty.call(e, u)) { var i = a ? Object.getOwnPropertyDescriptor(e, u) : null; i && (i.get || i.set) ? Object.defineProperty(n, u, i) : n[u] = e[u]; } return n.default = e, t && t.set(e, n), n; }
/**
 * @param {CustomBorders} customBordersPlugin The plugin instance.
 * @returns {object}
 */
function right(customBordersPlugin) {
  const borderDirection = customBordersPlugin.hot.isRtl() ? 'start' : 'end';
  return {
    key: 'borders:right',
    name() {
      let label = this.getTranslatedPhrase(C.CONTEXTMENU_ITEMS_BORDERS_RIGHT);
      const hasBorder = (0, _utils.checkSelectionBorders)(this, borderDirection);
      if (hasBorder) {
        label = (0, _utils.markSelected)(label);
      }
      return label;
    },
    callback(key, selected) {
      const hasBorder = (0, _utils.checkSelectionBorders)(this, borderDirection);
      customBordersPlugin.prepareBorder(selected, borderDirection, hasBorder);
    }
  };
}