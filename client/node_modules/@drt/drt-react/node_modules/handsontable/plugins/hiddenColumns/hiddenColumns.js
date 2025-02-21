"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
require("core-js/modules/es.array.push.js");
require("core-js/modules/es.set.difference.v2.js");
require("core-js/modules/es.set.intersection.v2.js");
require("core-js/modules/es.set.is-disjoint-from.v2.js");
require("core-js/modules/es.set.is-subset-of.v2.js");
require("core-js/modules/es.set.is-superset-of.v2.js");
require("core-js/modules/es.set.symmetric-difference.v2.js");
require("core-js/modules/es.set.union.v2.js");
require("core-js/modules/esnext.iterator.constructor.js");
require("core-js/modules/esnext.iterator.every.js");
require("core-js/modules/esnext.iterator.map.js");
var _base = require("../base");
var _element = require("../../helpers/dom/element");
var _number = require("../../helpers/number");
var _array = require("../../helpers/array");
var _predefinedItems = require("../contextMenu/predefinedItems");
var _hooks = require("../../core/hooks");
var _hideColumn = _interopRequireDefault(require("./contextMenuItem/hideColumn"));
var _showColumn = _interopRequireDefault(require("./contextMenuItem/showColumn"));
var _translations = require("../../translations");
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
_hooks.Hooks.getSingleton().register('beforeHideColumns');
_hooks.Hooks.getSingleton().register('afterHideColumns');
_hooks.Hooks.getSingleton().register('beforeUnhideColumns');
_hooks.Hooks.getSingleton().register('afterUnhideColumns');
const PLUGIN_KEY = exports.PLUGIN_KEY = 'hiddenColumns';
const PLUGIN_PRIORITY = exports.PLUGIN_PRIORITY = 310;

/* eslint-disable jsdoc/require-description-complete-sentence */

/**
 * @plugin HiddenColumns
 * @class HiddenColumns
 *
 * @description
 * The `HiddenColumns` plugin lets you [hide specified columns](@/guides/columns/column-hiding/column-hiding.md).
 *
 * "Hiding a column" means that the hidden column doesn't get rendered as a DOM element.
 *
 * The `HiddenColumns` plugin doesn't modify the source data,
 * and doesn't participate in data transformation
 * (the shape of the data returned by the [`getData*()` methods](@/api/core.md#getdata) stays intact).
 *
 * You can set the following configuration options:
 *
 * | Option | Required | Type | Default | Description |
 * |---|---|---|---|---|
 * | `columns` | No | Array | - | [Hides specified columns by default](@/guides/columns/column-hiding/column-hiding.md#step-1-specify-columns-hidden-by-default) |
 * | `indicators` | No | Boolean | `false` | [Shows UI indicators](@/guides/columns/column-hiding/column-hiding.md#step-2-show-ui-indicators) |
 * | `copyPasteEnabled` | No | Boolean | `true` | [Sets up copy/paste behavior](@/guides/columns/column-hiding/column-hiding.md#step-4-set-up-copy-and-paste-behavior) |
 *
 * @example
 *
 * ::: only-for javascript
 * ```js
 * const container = document.getElementById('example');
 * const hot = new Handsontable(container, {
 *   data: getData(),
 *   hiddenColumns: {
 *     copyPasteEnabled: true,
 *     indicators: true,
 *     columns: [1, 2, 5]
 *   }
 * });
 *
 * // access the `HiddenColumns` plugin's instance
 * const hiddenColumnsPlugin = hot.getPlugin('hiddenColumns');
 *
 * // hide a single column
 * hiddenColumnsPlugin.hideColumn(1);
 *
 * // hide multiple columns
 * hiddenColumnsPlugin.hideColumn(1, 2, 9);
 *
 * // hide multiple columns as an array
 * hiddenColumnsPlugin.hideColumns([1, 2, 9]);
 *
 * // unhide a single column
 * hiddenColumnsPlugin.showColumn(1);
 *
 * // unhide multiple columns
 * hiddenColumnsPlugin.showColumn(1, 2, 9);
 *
 * // unhide multiple columns as an array
 * hiddenColumnsPlugin.showColumns([1, 2, 9]);
 *
 * // to see your changes, re-render your Handsontable instance
 * hot.render();
 * ```
 * :::
 *
 * ::: only-for react
 * ```jsx
 * const hotRef = useRef(null);
 *
 * ...
 *
 * <HotTable
 *   ref={hotRef}
 *   data={getData()}
 *   hiddenColumns={{
 *     copyPasteEnabled: true,
 *     indicators: true,
 *     columns: [1, 2, 5]
 *   }}
 * />
 *
 * // access the `HiddenColumns` plugin's instance
 * const hot = hotRef.current.hotInstance;
 * const hiddenColumnsPlugin = hot.getPlugin('hiddenColumns');
 *
 * // hide a single column
 * hiddenColumnsPlugin.hideColumn(1);
 *
 * // hide multiple columns
 * hiddenColumnsPlugin.hideColumn(1, 2, 9);
 *
 * // hide multiple columns as an array
 * hiddenColumnsPlugin.hideColumns([1, 2, 9]);
 *
 * // unhide a single column
 * hiddenColumnsPlugin.showColumn(1);
 *
 * // unhide multiple columns
 * hiddenColumnsPlugin.showColumn(1, 2, 9);
 *
 * // unhide multiple columns as an array
 * hiddenColumnsPlugin.showColumns([1, 2, 9]);
 *
 * // to see your changes, re-render your Handsontable instance
 * hot.render();
 * ```
 * :::
 */
var _hiddenColumnsMap = /*#__PURE__*/new WeakMap();
var _HiddenColumns_brand = /*#__PURE__*/new WeakSet();
class HiddenColumns extends _base.BasePlugin {
  constructor() {
    super(...arguments);
    /**
     * Adds the additional column width for the hidden column indicators.
     *
     * @param {number|undefined} width Column width.
     * @param {number} column Visual column index.
     * @returns {number}
     */
    _classPrivateMethodInitSpec(this, _HiddenColumns_brand);
    /**
     * Map of hidden columns by the plugin.
     *
     * @private
     * @type {null|HidingMap}
     */
    _classPrivateFieldInitSpec(this, _hiddenColumnsMap, null);
  }
  static get PLUGIN_KEY() {
    return PLUGIN_KEY;
  }
  static get PLUGIN_PRIORITY() {
    return PLUGIN_PRIORITY;
  }
  static get DEFAULT_SETTINGS() {
    return {
      copyPasteEnabled: true,
      indicators: false,
      columns: []
    };
  }
  /**
   * Checks if the plugin is enabled in the handsontable settings. This method is executed in {@link Hooks#beforeInit}
   * hook and if it returns `true` then the {@link HiddenColumns#enablePlugin} method is called.
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
    _classPrivateFieldSet(_hiddenColumnsMap, this, new _translations.HidingMap());
    _classPrivateFieldGet(_hiddenColumnsMap, this).addLocalHook('init', () => _assertClassBrand(_HiddenColumns_brand, this, _onMapInit).call(this));
    this.hot.columnIndexMapper.registerMap(this.pluginName, _classPrivateFieldGet(_hiddenColumnsMap, this));
    this.addHook('afterContextMenuDefaultOptions', function () {
      for (var _len = arguments.length, args = new Array(_len), _key = 0; _key < _len; _key++) {
        args[_key] = arguments[_key];
      }
      return _assertClassBrand(_HiddenColumns_brand, _this, _onAfterContextMenuDefaultOptions).call(_this, ...args);
    });
    this.addHook('afterGetCellMeta', (row, col, cellProperties) => _assertClassBrand(_HiddenColumns_brand, this, _onAfterGetCellMeta).call(this, row, col, cellProperties));
    this.addHook('modifyColWidth', (width, col) => _assertClassBrand(_HiddenColumns_brand, this, _onModifyColWidth).call(this, width, col), 2);
    this.addHook('afterGetColHeader', function () {
      for (var _len2 = arguments.length, args = new Array(_len2), _key2 = 0; _key2 < _len2; _key2++) {
        args[_key2] = arguments[_key2];
      }
      return _assertClassBrand(_HiddenColumns_brand, _this, _onAfterGetColHeader).call(_this, ...args);
    });
    this.addHook('modifyCopyableRange', ranges => _assertClassBrand(_HiddenColumns_brand, this, _onModifyCopyableRange).call(this, ranges));
    super.enablePlugin();
  }

  /**
   * Updates the plugin's state.
   *
   * This method is executed when [`updateSettings()`](@/api/core.md#updatesettings) is invoked with any of the following configuration options:
   *  - [`hiddenColumns`](@/api/options.md#hiddencolumns)
   */
  updatePlugin() {
    this.disablePlugin();
    this.enablePlugin();
    super.updatePlugin();
  }

  /**
   * Disables the plugin functionality for this Handsontable instance.
   */
  disablePlugin() {
    this.hot.columnIndexMapper.unregisterMap(this.pluginName);
    super.disablePlugin();
    this.resetCellsMeta();
  }

  /**
   * Shows the provided columns.
   *
   * @param {number[]} columns Array of visual column indexes.
   */
  showColumns(columns) {
    const currentHideConfig = this.getHiddenColumns();
    const isValidConfig = this.isValidConfig(columns);
    let destinationHideConfig = currentHideConfig;
    const hidingMapValues = _classPrivateFieldGet(_hiddenColumnsMap, this).getValues().slice();
    const isAnyColumnShowed = columns.length > 0;
    if (isValidConfig && isAnyColumnShowed) {
      const physicalColumns = columns.map(visualColumn => this.hot.toPhysicalColumn(visualColumn));

      // Preparing new values for hiding map.
      (0, _array.arrayEach)(physicalColumns, physicalColumn => {
        hidingMapValues[physicalColumn] = false;
      });

      // Preparing new hiding config.
      destinationHideConfig = (0, _array.arrayReduce)(hidingMapValues, (hiddenIndexes, isHidden, physicalIndex) => {
        if (isHidden) {
          hiddenIndexes.push(this.hot.toVisualColumn(physicalIndex));
        }
        return hiddenIndexes;
      }, []);
    }
    const continueHiding = this.hot.runHooks('beforeUnhideColumns', currentHideConfig, destinationHideConfig, isValidConfig && isAnyColumnShowed);
    if (continueHiding === false) {
      return;
    }
    if (isValidConfig && isAnyColumnShowed) {
      _classPrivateFieldGet(_hiddenColumnsMap, this).setValues(hidingMapValues);
    }

    // @TODO Should call once per render cycle, currently fired separately in different plugins
    this.hot.view.adjustElementsSize();
    this.hot.runHooks('afterUnhideColumns', currentHideConfig, destinationHideConfig, isValidConfig && isAnyColumnShowed, isValidConfig && destinationHideConfig.length < currentHideConfig.length);
  }

  /**
   * Shows a single column.
   *
   * @param {...number} column Visual column index.
   */
  showColumn() {
    for (var _len3 = arguments.length, column = new Array(_len3), _key3 = 0; _key3 < _len3; _key3++) {
      column[_key3] = arguments[_key3];
    }
    this.showColumns(column);
  }

  /**
   * Hides the columns provided in the array.
   *
   * @param {number[]} columns Array of visual column indexes.
   */
  hideColumns(columns) {
    const currentHideConfig = this.getHiddenColumns();
    const isConfigValid = this.isValidConfig(columns);
    let destinationHideConfig = currentHideConfig;
    if (isConfigValid) {
      destinationHideConfig = Array.from(new Set(currentHideConfig.concat(columns)));
    }
    const continueHiding = this.hot.runHooks('beforeHideColumns', currentHideConfig, destinationHideConfig, isConfigValid);
    if (continueHiding === false) {
      return;
    }
    if (isConfigValid) {
      this.hot.batchExecution(() => {
        (0, _array.arrayEach)(columns, visualColumn => {
          _classPrivateFieldGet(_hiddenColumnsMap, this).setValueAtIndex(this.hot.toPhysicalColumn(visualColumn), true);
        });
      }, true);
    }
    this.hot.runHooks('afterHideColumns', currentHideConfig, destinationHideConfig, isConfigValid, isConfigValid && destinationHideConfig.length > currentHideConfig.length);
  }

  /**
   * Hides a single column.
   *
   * @param {...number} column Visual column index.
   */
  hideColumn() {
    for (var _len4 = arguments.length, column = new Array(_len4), _key4 = 0; _key4 < _len4; _key4++) {
      column[_key4] = arguments[_key4];
    }
    this.hideColumns(column);
  }

  /**
   * Returns an array of visual indexes of hidden columns.
   *
   * @returns {number[]}
   */
  getHiddenColumns() {
    return (0, _array.arrayMap)(_classPrivateFieldGet(_hiddenColumnsMap, this).getHiddenIndexes(), physicalColumnIndex => {
      return this.hot.toVisualColumn(physicalColumnIndex);
    });
  }

  /**
   * Checks if the provided column is hidden.
   *
   * @param {number} column Visual column index.
   * @returns {boolean}
   */
  isHidden(column) {
    return _classPrivateFieldGet(_hiddenColumnsMap, this).getValueAtIndex(this.hot.toPhysicalColumn(column)) || false;
  }

  /**
   * Get if trim config is valid. Check whether all of the provided column indexes are within the bounds of the table.
   *
   * @param {Array} hiddenColumns List of hidden column indexes.
   * @returns {boolean}
   */
  isValidConfig(hiddenColumns) {
    const nrOfColumns = this.hot.countCols();
    if (Array.isArray(hiddenColumns) && hiddenColumns.length > 0) {
      return hiddenColumns.every(visualColumn => Number.isInteger(visualColumn) && visualColumn >= 0 && visualColumn < nrOfColumns);
    }
    return false;
  }

  /**
   * Reset all rendered cells meta.
   *
   * @private
   */
  resetCellsMeta() {
    (0, _array.arrayEach)(this.hot.getCellsMeta(), meta => {
      meta.skipColumnOnPaste = false;
    });
  }
  /**
   * Destroys the plugin instance.
   */
  destroy() {
    _classPrivateFieldSet(_hiddenColumnsMap, this, null);
    super.destroy();
  }
}
exports.HiddenColumns = HiddenColumns;
function _onModifyColWidth(width, column) {
  // Hook is triggered internally only for the visible columns. Conditional will be handled for the API
  // calls of the `getColWidth` function on not visible indexes.
  if (this.isHidden(column)) {
    return 0;
  }
  if (this.getSetting('indicators') && (this.isHidden(column + 1) || this.isHidden(column - 1))) {
    // Add additional space for hidden column indicator.
    if (typeof width === 'number' && this.hot.hasColHeaders()) {
      return width + 15;
    }
  }
}
/**
 * Sets the copy-related cell meta.
 *
 * @param {number} row Visual row index.
 * @param {number} column Visual column index.
 * @param {object} cellProperties Object containing the cell properties.
 */
function _onAfterGetCellMeta(row, column, cellProperties) {
  if (this.getSetting('copyPasteEnabled') === false && this.isHidden(column)) {
    // Cell property handled by the `Autofill` and the `CopyPaste` plugins.
    cellProperties.skipColumnOnPaste = true;
  }
  if (this.isHidden(column - 1)) {
    cellProperties.className = cellProperties.className || '';
    if (cellProperties.className.indexOf('afterHiddenColumn') === -1) {
      cellProperties.className += ' afterHiddenColumn';
    }
  } else if (cellProperties.className) {
    const classArr = cellProperties.className.split(' ');
    if (classArr.length > 0) {
      const containAfterHiddenColumn = classArr.indexOf('afterHiddenColumn');
      if (containAfterHiddenColumn > -1) {
        classArr.splice(containAfterHiddenColumn, 1);
      }
      cellProperties.className = classArr.join(' ');
    }
  }
}
/**
 * Modifies the copyable range, accordingly to the provided config.
 *
 * @param {Array} ranges An array of objects defining copyable cells.
 * @returns {Array}
 */
function _onModifyCopyableRange(ranges) {
  // Ranges shouldn't be modified when `copyPasteEnabled` option is set to `true` (by default).
  if (this.getSetting('copyPasteEnabled')) {
    return ranges;
  }
  const newRanges = [];
  const pushRange = (startRow, endRow, startCol, endCol) => {
    newRanges.push({
      startRow,
      endRow,
      startCol,
      endCol
    });
  };
  (0, _array.arrayEach)(ranges, range => {
    let isHidden = true;
    let rangeStart = 0;
    (0, _number.rangeEach)(range.startCol, range.endCol, visualColumn => {
      if (this.isHidden(visualColumn)) {
        if (!isHidden) {
          pushRange(range.startRow, range.endRow, rangeStart, visualColumn - 1);
        }
        isHidden = true;
      } else {
        if (isHidden) {
          rangeStart = visualColumn;
        }
        if (visualColumn === range.endCol) {
          pushRange(range.startRow, range.endRow, rangeStart, visualColumn);
        }
        isHidden = false;
      }
    });
  });
  return newRanges;
}
/**
 * Adds the needed classes to the headers.
 *
 * @param {number} column Visual column index.
 * @param {HTMLElement} TH Header's TH element.
 */
function _onAfterGetColHeader(column, TH) {
  if (!this.getSetting('indicators') || column < 0) {
    return;
  }
  const classList = [];
  if (column >= 1 && this.isHidden(column - 1)) {
    classList.push('afterHiddenColumn');
  }
  if (column < this.hot.countCols() - 1 && this.isHidden(column + 1)) {
    classList.push('beforeHiddenColumn');
  }
  (0, _element.addClass)(TH, classList);
}
/**
 * Add Show-hide columns to context menu.
 *
 * @param {object} options An array of objects containing information about the pre-defined Context Menu items.
 */
function _onAfterContextMenuDefaultOptions(options) {
  options.items.push({
    name: _predefinedItems.SEPARATOR
  }, (0, _hideColumn.default)(this), (0, _showColumn.default)(this));
}
/**
 * On map initialized hook callback.
 */
function _onMapInit() {
  const columns = this.getSetting('columns');
  if (Array.isArray(columns)) {
    this.hideColumns(columns);
  }
}