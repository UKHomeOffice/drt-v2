import "core-js/modules/es.error.cause.js";
import "core-js/modules/es.array.push.js";
import "core-js/modules/es.set.difference.v2.js";
import "core-js/modules/es.set.intersection.v2.js";
import "core-js/modules/es.set.is-disjoint-from.v2.js";
import "core-js/modules/es.set.is-subset-of.v2.js";
import "core-js/modules/es.set.is-superset-of.v2.js";
import "core-js/modules/es.set.symmetric-difference.v2.js";
import "core-js/modules/es.set.union.v2.js";
import "core-js/modules/esnext.iterator.constructor.js";
import "core-js/modules/esnext.iterator.for-each.js";
import "core-js/modules/esnext.iterator.reduce.js";
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
import { BasePlugin } from "../base/index.mjs";
import DataManager from "./data/dataManager.mjs";
import CollapsingUI from "./ui/collapsing.mjs";
import HeadersUI from "./ui/headers.mjs";
import ContextMenuUI from "./ui/contextMenu.mjs";
import { error } from "../../helpers/console.mjs";
import { isArrayOfObjects } from "../../helpers/data.mjs";
import { TrimmingMap } from "../../translations/index.mjs";
import { EDITOR_EDIT_GROUP as SHORTCUTS_GROUP_EDITOR } from "../../shortcutContexts/index.mjs";
import RowMoveController from "./utils/rowMoveController.mjs";
export const PLUGIN_KEY = 'nestedRows';
export const PLUGIN_PRIORITY = 300;
const SHORTCUTS_GROUP = PLUGIN_KEY;

/* eslint-disable jsdoc/require-description-complete-sentence */
/**
 * Error message for the wrong data type error.
 */
const WRONG_DATA_TYPE_ERROR = 'The Nested Rows plugin requires an Array of Objects as a dataset to be' + ' provided. The plugin has been disabled.';

/**
 * @plugin NestedRows
 * @class NestedRows
 *
 * @description
 * Plugin responsible for displaying and operating on data sources with nested structures.
 */
var _skipRender = /*#__PURE__*/new WeakMap();
var _skipCoreAPIModifiers = /*#__PURE__*/new WeakMap();
var _NestedRows_brand = /*#__PURE__*/new WeakSet();
export class NestedRows extends BasePlugin {
  constructor() {
    super(...arguments);
    /**
     * `beforeRowMove` hook callback.
     *
     * @param {Array} rows Array of visual row indexes to be moved.
     * @param {number} finalIndex Visual row index, being a start index for the moved rows. Points to where the elements
     *   will be placed after the moving action. To check the visualization of the final index, please take a look at
     *   [documentation](@/guides/rows/row-summary/row-summary.md).
     * @param {undefined|number} dropIndex Visual row index, being a drop index for the moved rows. Points to where we
     *   are going to drop the moved elements. To check visualization of drop index please take a look at
     *   [documentation](@/guides/rows/row-summary/row-summary.md).
     * @param {boolean} movePossible Indicates if it's possible to move rows to the desired position.
     * @fires Hooks#afterRowMove
     * @returns {boolean}
     */
    _classPrivateMethodInitSpec(this, _NestedRows_brand);
    /**
     * Reference to the DataManager instance.
     *
     * @private
     * @type {object}
     */
    _defineProperty(this, "dataManager", null);
    /**
     * Reference to the HeadersUI instance.
     *
     * @private
     * @type {object}
     */
    _defineProperty(this, "headersUI", null);
    /**
     * Map of skipped rows by plugin.
     *
     * @private
     * @type {null|TrimmingMap}
     */
    _defineProperty(this, "collapsedRowsMap", null);
    /**
     * Allows skipping the render cycle if set as `true`.
     *
     * @type {boolean}
     */
    _classPrivateFieldInitSpec(this, _skipRender, false);
    /**
     * Allows skipping the internal Core methods call if set as `true`.
     *
     * @type {boolean}
     */
    _classPrivateFieldInitSpec(this, _skipCoreAPIModifiers, false);
  }
  static get PLUGIN_KEY() {
    return PLUGIN_KEY;
  }
  static get PLUGIN_PRIORITY() {
    return PLUGIN_PRIORITY;
  }
  /**
   * Checks if the plugin is enabled in the handsontable settings. This method is executed in {@link Hooks#beforeInit}
   * hook and if it returns `true` then the {@link NestedRows#enablePlugin} method is called.
   *
   * @returns {boolean}
   */
  isEnabled() {
    return !!this.hot.getSettings()[PLUGIN_KEY];
  }

  /**
   * Enables the plugin functionality for this Handsontable instance.
   */
  enablePlugin() {
    var _this = this;
    if (this.enabled) {
      return;
    }
    this.collapsedRowsMap = this.hot.rowIndexMapper.registerMap('nestedRows', new TrimmingMap());
    this.dataManager = new DataManager(this, this.hot);
    this.collapsingUI = new CollapsingUI(this, this.hot);
    this.headersUI = new HeadersUI(this, this.hot);
    this.contextMenuUI = new ContextMenuUI(this, this.hot);
    this.rowMoveController = new RowMoveController(this);
    this.addHook('afterInit', function () {
      for (var _len = arguments.length, args = new Array(_len), _key = 0; _key < _len; _key++) {
        args[_key] = arguments[_key];
      }
      return _assertClassBrand(_NestedRows_brand, _this, _onAfterInit).call(_this, ...args);
    });
    this.addHook('beforeViewRender', function () {
      for (var _len2 = arguments.length, args = new Array(_len2), _key2 = 0; _key2 < _len2; _key2++) {
        args[_key2] = arguments[_key2];
      }
      return _assertClassBrand(_NestedRows_brand, _this, _onBeforeViewRender).call(_this, ...args);
    });
    this.addHook('modifyRowData', function () {
      return _this.onModifyRowData(...arguments);
    });
    this.addHook('modifySourceLength', function () {
      return _this.onModifySourceLength(...arguments);
    });
    this.addHook('beforeDataSplice', function () {
      return _this.onBeforeDataSplice(...arguments);
    });
    this.addHook('filterData', function () {
      for (var _len3 = arguments.length, args = new Array(_len3), _key3 = 0; _key3 < _len3; _key3++) {
        args[_key3] = arguments[_key3];
      }
      return _assertClassBrand(_NestedRows_brand, _this, _onFilterData).call(_this, ...args);
    });
    this.addHook('afterContextMenuDefaultOptions', function () {
      for (var _len4 = arguments.length, args = new Array(_len4), _key4 = 0; _key4 < _len4; _key4++) {
        args[_key4] = arguments[_key4];
      }
      return _assertClassBrand(_NestedRows_brand, _this, _onAfterContextMenuDefaultOptions).call(_this, ...args);
    });
    this.addHook('afterGetRowHeader', function () {
      for (var _len5 = arguments.length, args = new Array(_len5), _key5 = 0; _key5 < _len5; _key5++) {
        args[_key5] = arguments[_key5];
      }
      return _assertClassBrand(_NestedRows_brand, _this, _onAfterGetRowHeader).call(_this, ...args);
    });
    this.addHook('beforeOnCellMouseDown', function () {
      for (var _len6 = arguments.length, args = new Array(_len6), _key6 = 0; _key6 < _len6; _key6++) {
        args[_key6] = arguments[_key6];
      }
      return _assertClassBrand(_NestedRows_brand, _this, _onBeforeOnCellMouseDown).call(_this, ...args);
    });
    this.addHook('beforeRemoveRow', function () {
      for (var _len7 = arguments.length, args = new Array(_len7), _key7 = 0; _key7 < _len7; _key7++) {
        args[_key7] = arguments[_key7];
      }
      return _assertClassBrand(_NestedRows_brand, _this, _onBeforeRemoveRow).call(_this, ...args);
    });
    this.addHook('afterRemoveRow', function () {
      for (var _len8 = arguments.length, args = new Array(_len8), _key8 = 0; _key8 < _len8; _key8++) {
        args[_key8] = arguments[_key8];
      }
      return _assertClassBrand(_NestedRows_brand, _this, _onAfterRemoveRow).call(_this, ...args);
    });
    this.addHook('beforeAddChild', function () {
      for (var _len9 = arguments.length, args = new Array(_len9), _key9 = 0; _key9 < _len9; _key9++) {
        args[_key9] = arguments[_key9];
      }
      return _assertClassBrand(_NestedRows_brand, _this, _onBeforeAddChild).call(_this, ...args);
    });
    this.addHook('afterAddChild', function () {
      for (var _len10 = arguments.length, args = new Array(_len10), _key10 = 0; _key10 < _len10; _key10++) {
        args[_key10] = arguments[_key10];
      }
      return _assertClassBrand(_NestedRows_brand, _this, _onAfterAddChild).call(_this, ...args);
    });
    this.addHook('beforeDetachChild', function () {
      for (var _len11 = arguments.length, args = new Array(_len11), _key11 = 0; _key11 < _len11; _key11++) {
        args[_key11] = arguments[_key11];
      }
      return _assertClassBrand(_NestedRows_brand, _this, _onBeforeDetachChild).call(_this, ...args);
    });
    this.addHook('afterDetachChild', function () {
      for (var _len12 = arguments.length, args = new Array(_len12), _key12 = 0; _key12 < _len12; _key12++) {
        args[_key12] = arguments[_key12];
      }
      return _assertClassBrand(_NestedRows_brand, _this, _onAfterDetachChild).call(_this, ...args);
    });
    this.addHook('modifyRowHeaderWidth', function () {
      for (var _len13 = arguments.length, args = new Array(_len13), _key13 = 0; _key13 < _len13; _key13++) {
        args[_key13] = arguments[_key13];
      }
      return _assertClassBrand(_NestedRows_brand, _this, _onModifyRowHeaderWidth).call(_this, ...args);
    });
    this.addHook('afterCreateRow', function () {
      for (var _len14 = arguments.length, args = new Array(_len14), _key14 = 0; _key14 < _len14; _key14++) {
        args[_key14] = arguments[_key14];
      }
      return _assertClassBrand(_NestedRows_brand, _this, _onAfterCreateRow).call(_this, ...args);
    });
    this.addHook('beforeRowMove', function () {
      for (var _len15 = arguments.length, args = new Array(_len15), _key15 = 0; _key15 < _len15; _key15++) {
        args[_key15] = arguments[_key15];
      }
      return _assertClassBrand(_NestedRows_brand, _this, _onBeforeRowMove).call(_this, ...args);
    });
    this.addHook('beforeLoadData', data => _assertClassBrand(_NestedRows_brand, this, _onBeforeLoadData).call(this, data));
    this.addHook('beforeUpdateData', data => _assertClassBrand(_NestedRows_brand, this, _onBeforeLoadData).call(this, data));
    this.registerShortcuts();
    super.enablePlugin();
  }

  /**
   * Disables the plugin functionality for this Handsontable instance.
   */
  disablePlugin() {
    this.hot.rowIndexMapper.unregisterMap('nestedRows');
    this.unregisterShortcuts();
    super.disablePlugin();
  }

  /**
   * Updates the plugin's state.
   *
   * This method is executed when [`updateSettings()`](@/api/core.md#updatesettings) is invoked with any of the following configuration options:
   *  - [`nestedRows`](@/api/options.md#nestedrows)
   */
  updatePlugin() {
    this.disablePlugin();

    // We store a state of the data manager.
    const currentSourceData = this.dataManager.getData();
    this.enablePlugin();

    // After enabling plugin previously stored data is restored.
    this.dataManager.updateWithData(currentSourceData);
    super.updatePlugin();
  }

  /**
   * Register shortcuts responsible for toggling collapsible columns.
   *
   * @private
   */
  registerShortcuts() {
    this.hot.getShortcutManager().getContext('grid').addShortcut({
      keys: [['Enter']],
      callback: () => {
        const {
          highlight
        } = this.hot.getSelectedRangeLast();
        const row = this.collapsingUI.translateTrimmedRow(highlight.row);
        if (this.collapsingUI.areChildrenCollapsed(row)) {
          this.collapsingUI.expandChildren(row);
        } else {
          this.collapsingUI.collapseChildren(row);
        }

        // prevent default Enter behavior (move to the next row within a selection range)
        return false;
      },
      runOnlyIf: () => {
        var _this$hot$getSelected, _this$hot$getSelected2;
        const highlight = (_this$hot$getSelected = this.hot.getSelectedRangeLast()) === null || _this$hot$getSelected === void 0 ? void 0 : _this$hot$getSelected.highlight;
        return highlight && ((_this$hot$getSelected2 = this.hot.getSelectedRangeLast()) === null || _this$hot$getSelected2 === void 0 ? void 0 : _this$hot$getSelected2.isSingle()) && this.hot.selection.isCellVisible(highlight) && highlight.col === -1 && highlight.row >= 0;
      },
      group: SHORTCUTS_GROUP,
      relativeToGroup: SHORTCUTS_GROUP_EDITOR,
      position: 'before'
    });
  }

  /**
   * Unregister shortcuts responsible for toggling collapsible columns.
   *
   * @private
   */
  unregisterShortcuts() {
    this.hot.getShortcutManager().getContext('grid').removeShortcutsByGroup(SHORTCUTS_GROUP);
  }
  /**
   * Enable the modify hook skipping flag - allows retrieving the data from Handsontable without this plugin's
   * modifications.
   *
   * @private
   */
  disableCoreAPIModifiers() {
    _classPrivateFieldSet(_skipCoreAPIModifiers, this, true);
  }

  /**
   * Disable the modify hook skipping flag.
   *
   * @private
   */
  enableCoreAPIModifiers() {
    _classPrivateFieldSet(_skipCoreAPIModifiers, this, false);
  }

  /**
   * `beforeOnCellMousedown` hook callback.
   *
   * @param {MouseEvent} event Mousedown event.
   * @param {object} coords Cell coords.
   * @param {HTMLElement} TD Clicked cell.
   */

  /**
   * The modifyRowData hook callback.
   *
   * @private
   * @param {number} row Visual row index.
   * @returns {boolean}
   */
  onModifyRowData(row) {
    if (_classPrivateFieldGet(_skipCoreAPIModifiers, this)) {
      return;
    }
    return this.dataManager.getDataObject(row);
  }

  /**
   * Modify the source data length to match the length of the nested structure.
   *
   * @private
   * @returns {number}
   */
  onModifySourceLength() {
    if (_classPrivateFieldGet(_skipCoreAPIModifiers, this)) {
      return;
    }
    return this.dataManager.countAllRows();
  }

  /**
   * @private
   * @param {number} index The index where the data was spliced.
   * @param {number} amount An amount of items to remove.
   * @param {object} element An element to add.
   * @returns {boolean}
   */
  onBeforeDataSplice(index, amount, element) {
    if (_classPrivateFieldGet(_skipCoreAPIModifiers, this) || this.dataManager.isRowHighestLevel(index)) {
      return true;
    }
    this.dataManager.spliceData(index, amount, element);
    return false;
  }

  /**
   * Provide custom source data filtering. It's handled by core method and replaces the native filtering.
   *
   * @param {number} index The index where the data filtering starts.
   * @param {number} amount An amount of rows which filtering applies to.
   * @param {number} physicalRows Physical row indexes.
   * @returns {Array}
   */

  /**
   * Destroys the plugin instance.
   */
  destroy() {
    super.destroy();
  }
}
function _onBeforeRowMove(rows, finalIndex, dropIndex, movePossible) {
  return this.rowMoveController.onBeforeRowMove(rows, finalIndex, dropIndex, movePossible);
}
function _onBeforeOnCellMouseDown(event, coords, TD) {
  this.collapsingUI.toggleState(event, coords, TD);
}
function _onFilterData(index, amount, physicalRows) {
  this.collapsingUI.collapsedRowsStash.stash();
  this.collapsingUI.collapsedRowsStash.trimStash(physicalRows[0], amount);
  this.collapsingUI.collapsedRowsStash.shiftStash(physicalRows[0], null, -1 * amount);
  this.dataManager.filterData(index, amount, physicalRows);
  _classPrivateFieldSet(_skipRender, this, true);
  return this.dataManager.getData().slice(); // Data contains reference sometimes.
}
/**
 * `afterContextMenuDefaultOptions` hook callback.
 *
 * @param {object} defaultOptions The default context menu items order.
 * @returns {boolean}
 */
function _onAfterContextMenuDefaultOptions(defaultOptions) {
  return this.contextMenuUI.appendOptions(defaultOptions);
}
/**
 * `afterGetRowHeader` hook callback.
 *
 * @param {number} row Row index.
 * @param {HTMLElement} TH Row header element.
 */
function _onAfterGetRowHeader(row, TH) {
  this.headersUI.appendLevelIndicators(row, TH);
}
/**
 * `modifyRowHeaderWidth` hook callback.
 *
 * @param {number} rowHeaderWidth The initial row header width(s).
 * @returns {number}
 */
function _onModifyRowHeaderWidth(rowHeaderWidth) {
  return Math.max(this.headersUI.rowHeaderWidthCache, rowHeaderWidth);
}
/**
 * `onAfterRemoveRow` hook callback.
 *
 * @param {number} index Removed row.
 * @param {number} amount Amount of removed rows.
 * @param {Array} logicRows An array of the removed physical rows.
 * @param {string} source Source of action.
 */
function _onAfterRemoveRow(index, amount, logicRows, source) {
  if (source === this.pluginName) {
    return;
  }
  this.hot._registerTimeout(() => {
    _classPrivateFieldSet(_skipRender, this, false);
    this.headersUI.updateRowHeaderWidth();
    this.collapsingUI.collapsedRowsStash.applyStash();
  });
}
/**
 * Callback for the `beforeRemoveRow` change list of removed physical indexes by reference. Removing parent node
 * has effect in removing children nodes.
 *
 * @param {number} index Visual index of starter row.
 * @param {number} amount Amount of rows to be removed.
 * @param {Array} physicalRows List of physical indexes.
 */
function _onBeforeRemoveRow(index, amount, physicalRows) {
  const modifiedPhysicalRows = Array.from(physicalRows.reduce((removedRows, physicalIndex) => {
    if (this.dataManager.isParent(physicalIndex)) {
      const children = this.dataManager.getDataObject(physicalIndex).__children;

      // Preserve a parent in the list of removed rows.
      removedRows.add(physicalIndex);
      if (Array.isArray(children)) {
        // Add a children to the list of removed rows.
        children.forEach(child => removedRows.add(this.dataManager.getRowIndex(child)));
      }
      return removedRows;
    }

    // Don't modify list of removed rows when already checked element isn't a parent.
    return removedRows.add(physicalIndex);
  }, new Set()));

  // Modifying hook's argument by the reference.
  physicalRows.length = 0;
  physicalRows.push(...modifiedPhysicalRows);
}
/**
 * `beforeAddChild` hook callback.
 */
function _onBeforeAddChild() {
  this.collapsingUI.collapsedRowsStash.stash();
}
/**
 * `afterAddChild` hook callback.
 *
 * @param {object} parent Parent element.
 * @param {object} element New child element.
 */
function _onAfterAddChild(parent, element) {
  this.collapsingUI.collapsedRowsStash.shiftStash(this.dataManager.getRowIndex(element));
  this.collapsingUI.collapsedRowsStash.applyStash();
  this.headersUI.updateRowHeaderWidth();
}
/**
 * `beforeDetachChild` hook callback.
 */
function _onBeforeDetachChild() {
  this.collapsingUI.collapsedRowsStash.stash();
}
/**
 * `afterDetachChild` hook callback.
 *
 * @param {object} parent Parent element.
 * @param {object} element New child element.
 * @param {number} finalElementRowIndex The final row index of the detached element.
 */
function _onAfterDetachChild(parent, element, finalElementRowIndex) {
  this.collapsingUI.collapsedRowsStash.shiftStash(finalElementRowIndex, null, -1);
  this.collapsingUI.collapsedRowsStash.applyStash();
  this.headersUI.updateRowHeaderWidth();
}
/**
 * `afterCreateRow` hook callback.
 */
function _onAfterCreateRow() {
  this.dataManager.rewriteCache();
}
/**
 * `afterInit` hook callback.
 */
function _onAfterInit() {
  this.headersUI.updateRowHeaderWidth();
}
/**
 * `beforeViewRender` hook callback.
 *
 * @param {boolean} force Indicates if the render call was triggered by a change of settings or data.
 * @param {object} skipRender An object, holder for skipRender functionality.
 */
function _onBeforeViewRender(force, skipRender) {
  if (_classPrivateFieldGet(_skipRender, this)) {
    skipRender.skipRender = true;
  }
}
/**
 * `beforeLoadData` hook callback.
 *
 * @param {Array} data The source data.
 */
function _onBeforeLoadData(data) {
  if (!isArrayOfObjects(data)) {
    error(WRONG_DATA_TYPE_ERROR);
    this.hot.getSettings()[PLUGIN_KEY] = false;
    this.disablePlugin();
    return;
  }
  this.dataManager.setData(data);
  this.dataManager.rewriteCache();
}