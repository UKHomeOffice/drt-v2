"use strict";

exports.__esModule = true;
exports.default = alignmentItem;
var _utils = require("../utils");
var _separator = require("./separator");
var C = _interopRequireWildcard(require("../../../i18n/constants"));
function _getRequireWildcardCache(e) { if ("function" != typeof WeakMap) return null; var r = new WeakMap(), t = new WeakMap(); return (_getRequireWildcardCache = function (e) { return e ? t : r; })(e); }
function _interopRequireWildcard(e, r) { if (!r && e && e.__esModule) return e; if (null === e || "object" != typeof e && "function" != typeof e) return { default: e }; var t = _getRequireWildcardCache(r); if (t && t.has(e)) return t.get(e); var n = { __proto__: null }, a = Object.defineProperty && Object.getOwnPropertyDescriptor; for (var u in e) if ("default" !== u && {}.hasOwnProperty.call(e, u)) { var i = a ? Object.getOwnPropertyDescriptor(e, u) : null; i && (i.get || i.set) ? Object.defineProperty(n, u, i) : n[u] = e[u]; } return n.default = e, t && t.set(e, n), n; }
const KEY = exports.KEY = 'alignment';

/**
 * @returns {object}
 */
function alignmentItem() {
  return {
    key: KEY,
    name() {
      return this.getTranslatedPhrase(C.CONTEXTMENU_ITEMS_ALIGNMENT);
    },
    disabled() {
      if (this.countRows() === 0 || this.countCols() === 0) {
        return true;
      }
      const range = this.getSelectedRangeLast();
      if (!range) {
        return true;
      }
      if (range.isSingleHeader()) {
        return true;
      }
      return !(this.getSelectedRange() && !this.selection.isSelectedByCorner());
    },
    submenu: {
      items: [{
        key: `${KEY}:left`,
        name() {
          return this.getTranslatedPhrase(C.CONTEXTMENU_ITEMS_ALIGNMENT_LEFT);
        },
        callback() {
          const selectedRange = this.getSelectedRange();
          const stateBefore = (0, _utils.getAlignmentClasses)(selectedRange, (row, col) => this.getCellMeta(row, col).className);
          const type = 'horizontal';
          const alignment = 'htLeft';
          this.runHooks('beforeCellAlignment', stateBefore, selectedRange, type, alignment);
          (0, _utils.align)(selectedRange, type, alignment, (row, col) => this.getCellMeta(row, col), (row, col, key, value) => this.setCellMeta(row, col, key, value));
          this.render();
        },
        disabled: false
      }, {
        key: `${KEY}:center`,
        name() {
          return this.getTranslatedPhrase(C.CONTEXTMENU_ITEMS_ALIGNMENT_CENTER);
        },
        callback() {
          const selectedRange = this.getSelectedRange();
          const stateBefore = (0, _utils.getAlignmentClasses)(selectedRange, (row, col) => this.getCellMeta(row, col).className);
          const type = 'horizontal';
          const alignment = 'htCenter';
          this.runHooks('beforeCellAlignment', stateBefore, selectedRange, type, alignment);
          (0, _utils.align)(selectedRange, type, alignment, (row, col) => this.getCellMeta(row, col), (row, col, key, value) => this.setCellMeta(row, col, key, value));
          this.render();
        },
        disabled: false
      }, {
        key: `${KEY}:right`,
        name() {
          return this.getTranslatedPhrase(C.CONTEXTMENU_ITEMS_ALIGNMENT_RIGHT);
        },
        callback() {
          const selectedRange = this.getSelectedRange();
          const stateBefore = (0, _utils.getAlignmentClasses)(selectedRange, (row, col) => this.getCellMeta(row, col).className);
          const type = 'horizontal';
          const alignment = 'htRight';
          this.runHooks('beforeCellAlignment', stateBefore, selectedRange, type, alignment);
          (0, _utils.align)(selectedRange, type, alignment, (row, col) => this.getCellMeta(row, col), (row, col, key, value) => this.setCellMeta(row, col, key, value));
          this.render();
        },
        disabled: false
      }, {
        key: `${KEY}:justify`,
        name() {
          return this.getTranslatedPhrase(C.CONTEXTMENU_ITEMS_ALIGNMENT_JUSTIFY);
        },
        callback() {
          const selectedRange = this.getSelectedRange();
          const stateBefore = (0, _utils.getAlignmentClasses)(selectedRange, (row, col) => this.getCellMeta(row, col).className);
          const type = 'horizontal';
          const alignment = 'htJustify';
          this.runHooks('beforeCellAlignment', stateBefore, selectedRange, type, alignment);
          (0, _utils.align)(selectedRange, type, alignment, (row, col) => this.getCellMeta(row, col), (row, col, key, value) => this.setCellMeta(row, col, key, value));
          this.render();
        },
        disabled: false
      }, {
        name: _separator.KEY
      }, {
        key: `${KEY}:top`,
        name() {
          return this.getTranslatedPhrase(C.CONTEXTMENU_ITEMS_ALIGNMENT_TOP);
        },
        callback() {
          const selectedRange = this.getSelectedRange();
          const stateBefore = (0, _utils.getAlignmentClasses)(selectedRange, (row, col) => this.getCellMeta(row, col).className);
          const type = 'vertical';
          const alignment = 'htTop';
          this.runHooks('beforeCellAlignment', stateBefore, selectedRange, type, alignment);
          (0, _utils.align)(selectedRange, type, alignment, (row, col) => this.getCellMeta(row, col), (row, col, key, value) => this.setCellMeta(row, col, key, value));
          this.render();
        },
        disabled: false
      }, {
        key: `${KEY}:middle`,
        name() {
          return this.getTranslatedPhrase(C.CONTEXTMENU_ITEMS_ALIGNMENT_MIDDLE);
        },
        callback() {
          const selectedRange = this.getSelectedRange();
          const stateBefore = (0, _utils.getAlignmentClasses)(selectedRange, (row, col) => this.getCellMeta(row, col).className);
          const type = 'vertical';
          const alignment = 'htMiddle';
          this.runHooks('beforeCellAlignment', stateBefore, selectedRange, type, alignment);
          (0, _utils.align)(selectedRange, type, alignment, (row, col) => this.getCellMeta(row, col), (row, col, key, value) => this.setCellMeta(row, col, key, value));
          this.render();
        },
        disabled: false
      }, {
        key: `${KEY}:bottom`,
        name() {
          return this.getTranslatedPhrase(C.CONTEXTMENU_ITEMS_ALIGNMENT_BOTTOM);
        },
        callback() {
          const selectedRange = this.getSelectedRange();
          const stateBefore = (0, _utils.getAlignmentClasses)(selectedRange, (row, col) => this.getCellMeta(row, col).className);
          const type = 'vertical';
          const alignment = 'htBottom';
          this.runHooks('beforeCellAlignment', stateBefore, selectedRange, type, alignment);
          (0, _utils.align)(selectedRange, type, alignment, (row, col) => this.getCellMeta(row, col), (row, col, key, value) => this.setCellMeta(row, col, key, value));
          this.render();
        },
        disabled: false
      }]
    }
  };
}