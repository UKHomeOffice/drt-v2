import "core-js/modules/es.error.cause.js";
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
import { rangeEach } from "../../../helpers/number.mjs";
import { arrayEach } from "../../../helpers/array.mjs";
import * as C from "../../../i18n/constants.mjs";
import BaseUI from "./_base.mjs";
/**
 * Class responsible for the Context Menu entries for the Nested Rows plugin.
 *
 * @private
 * @class ContextMenuUI
 * @augments BaseUI
 */
var _menuEntries = /*#__PURE__*/new WeakMap();
class ContextMenuUI extends BaseUI {
  constructor() {
    super(...arguments);
    /**
     * Reference to the DataManager instance connected with the Nested Rows plugin.
     *
     * @type {DataManager}
     */
    _defineProperty(this, "dataManager", this.plugin.dataManager);
    _classPrivateFieldInitSpec(this, _menuEntries, {
      row_above: (key, selection) => {
        const lastSelection = selection[selection.length - 1];
        this.dataManager.addSibling(lastSelection.start.row, 'above');
      },
      row_below: (key, selection) => {
        const lastSelection = selection[selection.length - 1];
        this.dataManager.addSibling(lastSelection.start.row, 'below');
      }
    });
  }
  /**
   * Append options to the context menu. (Propagated from the `afterContextMenuDefaultOptions` hook callback)
   * f.
   *
   * @private
   * @param {object} defaultOptions Default context menu options.
   * @returns {*}
   */
  appendOptions(defaultOptions) {
    const newEntries = [{
      key: 'add_child',
      name() {
        return this.getTranslatedPhrase(C.CONTEXTMENU_ITEMS_NESTED_ROWS_INSERT_CHILD);
      },
      callback: () => {
        const translatedRowIndex = this.dataManager.translateTrimmedRow(this.hot.getSelectedLast()[0]);
        const parent = this.dataManager.getDataObject(translatedRowIndex);
        this.dataManager.addChild(parent);
      },
      disabled: () => {
        const selected = this.hot.getSelectedLast();
        return !selected || selected[0] < 0 || this.hot.selection.isSelectedByColumnHeader() || this.hot.countRows() >= this.hot.getSettings().maxRows;
      }
    }, {
      key: 'detach_from_parent',
      name() {
        return this.getTranslatedPhrase(C.CONTEXTMENU_ITEMS_NESTED_ROWS_DETACH_CHILD);
      },
      callback: () => {
        this.dataManager.detachFromParent(this.hot.getSelectedLast());
      },
      disabled: () => {
        const selected = this.hot.getSelectedLast();
        const translatedRowIndex = this.dataManager.translateTrimmedRow(selected[0]);
        const parent = this.dataManager.getRowParent(translatedRowIndex);
        return !parent || !selected || selected[0] < 0 || this.hot.selection.isSelectedByColumnHeader() || this.hot.countRows() >= this.hot.getSettings().maxRows;
      }
    }, {
      name: '---------'
    }];
    rangeEach(0, defaultOptions.items.length - 1, i => {
      if (i === 0) {
        arrayEach(newEntries, (val, j) => {
          defaultOptions.items.splice(i + j, 0, val);
        });
        return false;
      }
    });
    return this.modifyRowInsertingOptions(defaultOptions);
  }

  /**
   * Modify how the row inserting options work.
   *
   * @private
   * @param {object} defaultOptions Default context menu items.
   * @returns {*}
   */
  modifyRowInsertingOptions(defaultOptions) {
    rangeEach(0, defaultOptions.items.length - 1, i => {
      const option = _classPrivateFieldGet(_menuEntries, this)[defaultOptions.items[i].key];
      if (option !== null && option !== undefined) {
        defaultOptions.items[i].callback = option;
      }
    });
    return defaultOptions;
  }
}
export default ContextMenuUI;