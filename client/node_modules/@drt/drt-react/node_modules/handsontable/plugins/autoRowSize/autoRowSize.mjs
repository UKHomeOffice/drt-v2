import "core-js/modules/es.error.cause.js";
import "core-js/modules/es.array.push.js";
import "core-js/modules/esnext.iterator.constructor.js";
import "core-js/modules/esnext.iterator.filter.js";
import "core-js/modules/esnext.iterator.for-each.js";
import "core-js/modules/esnext.iterator.reduce.js";
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
import { BasePlugin } from "../base/index.mjs";
import { cancelAnimationFrame, requestAnimationFrame } from "../../helpers/feature.mjs";
import GhostTable from "../../utils/ghostTable.mjs";
import { isObject } from "../../helpers/object.mjs";
import { valueAccordingPercent, rangeEach } from "../../helpers/number.mjs";
import SamplesGenerator from "../../utils/samplesGenerator.mjs";
import { isPercentValue } from "../../helpers/string.mjs";
import { PhysicalIndexToValueMap as IndexToValueMap } from "../../translations/index.mjs";
export const PLUGIN_KEY = 'autoRowSize';
export const PLUGIN_PRIORITY = 40;
const ROW_WIDTHS_MAP_NAME = 'autoRowSize';

/* eslint-disable jsdoc/require-description-complete-sentence */
/**
 * @plugin AutoRowSize
 * @class AutoRowSize
 * @description
 * The `AutoRowSize` plugin allows you to set row heights based on their highest cells.
 *
 * By default, the plugin is declared as `undefined`, which makes it disabled (same as if it was declared as `false`).
 * Enabling this plugin may decrease the overall table performance, as it needs to calculate the heights of all cells to
 * resize the rows accordingly.
 * If you experience problems with the performance, try turning this feature off and declaring the row heights manually.
 *
 * But, to display Handsontable's scrollbar in a proper size, you need to enable the `AutoRowSize` plugin,
 * by setting the [`autoRowSize`](@/api/options.md#autoRowSize) option to `true`.
 *
 * Row height calculations are divided into sync and async part. Each of this parts has their own advantages and
 * disadvantages. Synchronous calculations are faster but they block the browser UI, while the slower asynchronous
 * operations don't block the browser UI.
 *
 * To configure the sync/async distribution, you can pass an absolute value (number of rows) or a percentage value to a config object:
 * ```js
 * // as a number (300 rows in sync, rest async)
 * autoRowSize: {syncLimit: 300},
 *
 * // as a string (percent)
 * autoRowSize: {syncLimit: '40%'},
 *
 * // allow sample duplication
 * autoRowSize: {syncLimit: '40%', allowSampleDuplicates: true},
 * ```
 *
 * You can also use the `allowSampleDuplicates` option to allow sampling duplicate values when calculating the row
 * height. __Note__, that this might have a negative impact on performance.
 *
 * To configure this plugin see {@link Options#autoRowSize}.
 *
 * @example
 *
 * ::: only-for javascript
 * ```js
 * const hot = new Handsontable(document.getElementById('example'), {
 *   data: getData(),
 *   autoRowSize: true
 * });
 * // Access to plugin instance:
 * const plugin = hot.getPlugin('autoRowSize');
 *
 * plugin.getRowHeight(4);
 *
 * if (plugin.isEnabled()) {
 *   // code...
 * }
 * ```
 * :::
 *
 * ::: only-for react
 * ```jsx
 * const hotRef = useRef(null);
 *
 * ...
 *
 * // First, let's contruct Handsontable
 * <HotTable
 *   ref={hotRef}
 *   data={getData()}
 *   autoRowSize={true}
 * />
 *
 * ...
 *
 * // Access to plugin instance:
 * const hot = hotRef.current.hotInstance;
 * const plugin = hot.getPlugin('autoRowSize');
 *
 * plugin.getRowHeight(4);
 *
 * if (plugin.isEnabled()) {
 *   // code...
 * }
 * ```
 * :::
 */
/* eslint-enable jsdoc/require-description-complete-sentence */
var _visualRowsToRefresh = /*#__PURE__*/new WeakMap();
var _AutoRowSize_brand = /*#__PURE__*/new WeakSet();
export class AutoRowSize extends BasePlugin {
  static get PLUGIN_KEY() {
    return PLUGIN_KEY;
  }
  static get PLUGIN_PRIORITY() {
    return PLUGIN_PRIORITY;
  }
  static get SETTING_KEYS() {
    return true;
  }
  static get DEFAULT_SETTINGS() {
    return {
      useHeaders: true,
      samplingRatio: null,
      allowSampleDuplicates: false
    };
  }
  static get CALCULATION_STEP() {
    return 50;
  }
  static get SYNC_CALCULATION_LIMIT() {
    return 500;
  }

  /**
   * Columns header's height cache.
   *
   * @private
   * @type {number}
   */

  constructor(hotInstance) {
    super(hotInstance);
    /**
     * Calculates specific rows height (overwrite cache values).
     *
     * @param {number[]} visualRows List of visual rows to calculate.
     */
    _classPrivateMethodInitSpec(this, _AutoRowSize_brand);
    _defineProperty(this, "headerHeight", null);
    /**
     * Instance of {@link GhostTable} for rows and columns size calculations.
     *
     * @private
     * @type {GhostTable}
     */
    _defineProperty(this, "ghostTable", new GhostTable(this.hot));
    /**
     * Instance of {@link SamplesGenerator} for generating samples necessary for rows height calculations.
     *
     * @private
     * @type {SamplesGenerator}
     */
    _defineProperty(this, "samplesGenerator", new SamplesGenerator((row, column) => {
      const physicalRow = this.hot.toPhysicalRow(row);
      const physicalColumn = this.hot.toPhysicalColumn(column);
      if (this.hot.rowIndexMapper.isHidden(physicalRow) || this.hot.columnIndexMapper.isHidden(physicalColumn)) {
        return false;
      }
      if (row >= 0 && column >= 0) {
        const cellMeta = this.hot.getCellMeta(row, column);
        if (cellMeta.hidden) {
          // do not generate samples for cells that are covered by merged cell (null values)
          return false;
        }
      }
      let cellValue;
      if (row >= 0) {
        cellValue = this.hot.getDataAtCell(row, column);
      } else if (row === -1) {
        cellValue = this.hot.getColHeader(column);
      }
      return {
        value: cellValue
      };
    }));
    /**
     * `true` if the size calculation is in progress.
     *
     * @type {boolean}
     */
    _defineProperty(this, "inProgress", false);
    /**
     * Number of already measured rows (we already know their sizes).
     *
     * @type {number}
     */
    _defineProperty(this, "measuredRows", 0);
    /**
     * PhysicalIndexToValueMap to keep and track heights for physical row indexes.
     *
     * @private
     * @type {PhysicalIndexToValueMap}
     */
    _defineProperty(this, "rowHeightsMap", new IndexToValueMap());
    /**
     * An array of row indexes whose height will be recalculated.
     *
     * @type {number[]}
     */
    _classPrivateFieldInitSpec(this, _visualRowsToRefresh, []);
    this.hot.rowIndexMapper.registerMap(ROW_WIDTHS_MAP_NAME, this.rowHeightsMap);

    // Leave the listener active to allow auto-sizing the rows when the plugin is disabled.
    // This is necessary for height recalculation for resize handler doubleclick (ManualRowResize).
    this.addHook('beforeRowResize', (size, row, isDblClick) => _assertClassBrand(_AutoRowSize_brand, this, _onBeforeRowResize).call(this, size, row, isDblClick));
  }

  /**
   * Checks if the plugin is enabled in the handsontable settings. This method is executed in {@link Hooks#beforeInit}
   * hook and if it returns `true` then the {@link AutoRowSize#enablePlugin} method is called.
   *
   * @returns {boolean}
   */
  isEnabled() {
    const settings = this.hot.getSettings()[PLUGIN_KEY];
    return settings === true || isObject(settings);
  }

  /**
   * Enables the plugin functionality for this Handsontable instance.
   */
  enablePlugin() {
    var _this = this;
    if (this.enabled) {
      return;
    }
    this.samplesGenerator.setAllowDuplicates(this.getSetting('allowSampleDuplicates'));
    const samplingRatio = this.getSetting('samplingRatio');
    if (samplingRatio && !isNaN(samplingRatio)) {
      this.samplesGenerator.setSampleCount(parseInt(samplingRatio, 10));
    }
    this.addHook('afterLoadData', function () {
      for (var _len = arguments.length, args = new Array(_len), _key = 0; _key < _len; _key++) {
        args[_key] = arguments[_key];
      }
      return _assertClassBrand(_AutoRowSize_brand, _this, _onAfterLoadData).call(_this, ...args);
    });
    this.addHook('beforeChangeRender', function () {
      for (var _len2 = arguments.length, args = new Array(_len2), _key2 = 0; _key2 < _len2; _key2++) {
        args[_key2] = arguments[_key2];
      }
      return _assertClassBrand(_AutoRowSize_brand, _this, _onBeforeChange).call(_this, ...args);
    });
    this.addHook('beforeColumnResize', () => this.recalculateAllRowsHeight());
    this.addHook('afterFormulasValuesUpdate', function () {
      for (var _len3 = arguments.length, args = new Array(_len3), _key3 = 0; _key3 < _len3; _key3++) {
        args[_key3] = arguments[_key3];
      }
      return _assertClassBrand(_AutoRowSize_brand, _this, _onAfterFormulasValuesUpdate).call(_this, ...args);
    });
    this.addHook('beforeRender', () => _assertClassBrand(_AutoRowSize_brand, this, _onBeforeRender).call(this));
    this.addHook('modifyRowHeight', (height, row) => this.getRowHeight(row, height));
    this.addHook('init', () => _assertClassBrand(_AutoRowSize_brand, this, _onInit).call(this));
    this.addHook('modifyColumnHeaderHeight', () => this.getColumnHeaderHeight());
    super.enablePlugin();
  }

  /**
   * Disables the plugin functionality for this Handsontable instance.
   */
  disablePlugin() {
    this.headerHeight = null;
    super.disablePlugin();

    // Leave the listener active to allow auto-sizing the rows when the plugin is disabled.
    // This is necessary for height recalculation for resize handler doubleclick (ManualRowResize).
    this.addHook('beforeRowResize', (size, row, isDblClick) => _assertClassBrand(_AutoRowSize_brand, this, _onBeforeRowResize).call(this, size, row, isDblClick));
  }

  /**
   * Calculates heights for visible rows in the viewport only.
   */
  calculateVisibleRowsHeight() {
    // Keep last row heights unchanged for situation when all columns was deleted or trimmed
    if (!this.hot.countCols()) {
      return;
    }
    const firstVisibleRow = this.getFirstVisibleRow();
    const lastVisibleRow = this.getLastVisibleRow();
    if (firstVisibleRow === -1 || lastVisibleRow === -1) {
      return;
    }
    const overwriteCache = this.hot.renderCall;
    this.calculateRowsHeight({
      from: firstVisibleRow,
      to: lastVisibleRow
    }, undefined, overwriteCache);
  }

  /**
   * Calculate a given rows height.
   *
   * @param {number|object} rowRange Row index or an object with `from` and `to` indexes as a range.
   * @param {number|object} colRange Column index or an object with `from` and `to` indexes as a range.
   * @param {boolean} [overwriteCache=false] If `true` the calculation will be processed regardless of whether the width exists in the cache.
   */
  calculateRowsHeight() {
    let rowRange = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : {
      from: 0,
      to: this.hot.countRows() - 1
    };
    let colRange = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : {
      from: 0,
      to: this.hot.countCols() - 1
    };
    let overwriteCache = arguments.length > 2 && arguments[2] !== undefined ? arguments[2] : false;
    const rowsRange = typeof rowRange === 'number' ? {
      from: rowRange,
      to: rowRange
    } : rowRange;
    const columnsRange = typeof colRange === 'number' ? {
      from: colRange,
      to: colRange
    } : colRange;
    if (this.hot.getColHeader(0) !== null) {
      const samples = this.samplesGenerator.generateRowSamples(-1, columnsRange);
      this.ghostTable.addColumnHeadersRow(samples.get(-1));
    }
    rangeEach(rowsRange.from, rowsRange.to, visualRow => {
      let physicalRow = this.hot.toPhysicalRow(visualRow);
      if (physicalRow === null) {
        physicalRow = visualRow;
      }

      // For rows we must calculate row height even when user had set height value manually.
      // We can shrink column but cannot shrink rows!
      if (overwriteCache || this.rowHeightsMap.getValueAtIndex(physicalRow) === null) {
        const samples = this.samplesGenerator.generateRowSamples(visualRow, columnsRange);
        samples.forEach((sample, row) => this.ghostTable.addRow(row, sample));
      }
    });
    if (this.ghostTable.rows.length) {
      this.hot.batchExecution(() => {
        this.ghostTable.getHeights((row, height) => {
          if (row < 0) {
            this.headerHeight = height;
          } else {
            this.rowHeightsMap.setValueAtIndex(this.hot.toPhysicalRow(row), height);
          }
        });
      }, true);
      this.measuredRows = rowsRange.to + 1;
      this.ghostTable.clean();
    }
  }

  /**
   * Calculate all rows heights. The calculated row will be cached in the {@link AutoRowSize#heights} property.
   * To retrieve height for specified row use {@link AutoRowSize#getRowHeight} method.
   *
   * @param {object|number} colRange Row index or an object with `from` and `to` properties which define row range.
   * @param {boolean} [overwriteCache] If `true` the calculation will be processed regardless of whether the width exists in the cache.
   */
  calculateAllRowsHeight() {
    let colRange = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : {
      from: 0,
      to: this.hot.countCols() - 1
    };
    let overwriteCache = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : false;
    let current = 0;
    const length = this.hot.countRows() - 1;
    let timer = null;
    this.inProgress = true;
    const loop = () => {
      // When hot was destroyed after calculating finished cancel frame
      if (!this.hot) {
        cancelAnimationFrame(timer);
        this.inProgress = false;
        return;
      }
      this.calculateRowsHeight({
        from: current,
        to: Math.min(current + AutoRowSize.CALCULATION_STEP, length)
      }, colRange, overwriteCache);
      current = current + AutoRowSize.CALCULATION_STEP + 1;
      if (current < length) {
        timer = requestAnimationFrame(loop);
      } else {
        cancelAnimationFrame(timer);
        this.inProgress = false;

        // @TODO Should call once per render cycle, currently fired separately in different plugins
        this.hot.view.adjustElementsSize();

        // tmp
        if (this.hot.view._wt.wtOverlays.inlineStartOverlay.needFullRender) {
          this.hot.view._wt.wtOverlays.inlineStartOverlay.clone.draw();
        }
      }
    };
    const syncLimit = this.getSyncCalculationLimit();

    // sync
    if (syncLimit >= 0) {
      this.calculateRowsHeight({
        from: 0,
        to: syncLimit
      }, colRange, overwriteCache);
      current = syncLimit + 1;
    }
    // async
    if (current < length) {
      loop();
    } else {
      this.inProgress = false;
      this.hot.view.adjustElementsSize();
    }
  }
  /**
   * Recalculates all rows height (overwrite cache values).
   */
  recalculateAllRowsHeight() {
    if (this.hot.view.isVisible()) {
      this.calculateAllRowsHeight({
        from: 0,
        to: this.hot.countCols() - 1
      }, true);
    }
  }

  /**
   * Gets value which tells how many rows should be calculated synchronously (rest of the rows will be calculated
   * asynchronously). The limit is calculated based on `syncLimit` set to autoRowSize option (see {@link Options#autoRowSize}).
   *
   * @returns {number}
   */
  getSyncCalculationLimit() {
    const settings = this.hot.getSettings()[PLUGIN_KEY];
    /* eslint-disable no-bitwise */
    let limit = AutoRowSize.SYNC_CALCULATION_LIMIT;
    const rowsLimit = this.hot.countRows() - 1;
    if (isObject(settings)) {
      limit = settings.syncLimit;
      if (isPercentValue(limit)) {
        limit = valueAccordingPercent(rowsLimit, limit);
      } else {
        // Force to Number
        limit >>= 0;
      }
    }
    return Math.min(limit, rowsLimit);
  }

  /**
   * Get a row's height, as measured in the DOM.
   *
   * The height returned includes 1 px of the row's bottom border.
   *
   * Mind that this method is different from the
   * [`getRowHeight()`](@/api/core.md#getrowheight) method
   * of Handsontable's [Core](@/api/core.md).
   *
   * @param {number} row A visual row index.
   * @param {number} [defaultHeight] If no height is found, `defaultHeight` is returned instead.
   * @returns {number} The height of the specified row, in pixels.
   */
  getRowHeight(row, defaultHeight) {
    const cachedHeight = row < 0 ? this.headerHeight : this.rowHeightsMap.getValueAtIndex(this.hot.toPhysicalRow(row));
    let height = defaultHeight;
    if (cachedHeight !== null && cachedHeight > (defaultHeight || 0)) {
      height = cachedHeight;
    }
    return height;
  }

  /**
   * Get the calculated column header height.
   *
   * @returns {number|undefined}
   */
  getColumnHeaderHeight() {
    return this.headerHeight;
  }

  /**
   * Get the first visible row.
   *
   * @returns {number} Returns row index, -1 if table is not rendered or if there are no rows to base the the calculations on.
   */
  getFirstVisibleRow() {
    var _this$hot$getFirstRen;
    return (_this$hot$getFirstRen = this.hot.getFirstRenderedVisibleRow()) !== null && _this$hot$getFirstRen !== void 0 ? _this$hot$getFirstRen : -1;
  }

  /**
   * Gets the last visible row.
   *
   * @returns {number} Returns row index or -1 if table is not rendered.
   */
  getLastVisibleRow() {
    var _this$hot$getLastRend;
    return (_this$hot$getLastRend = this.hot.getLastRenderedVisibleRow()) !== null && _this$hot$getLastRend !== void 0 ? _this$hot$getLastRend : -1;
  }

  /**
   * Clears cache of calculated row heights. If you want to clear only selected rows pass an array with their indexes.
   * Otherwise whole cache will be cleared.
   *
   * @param {number[]} [physicalRows] List of physical row indexes to clear.
   */
  clearCache(physicalRows) {
    this.headerHeight = null;
    if (Array.isArray(physicalRows)) {
      this.hot.batchExecution(() => {
        physicalRows.forEach(physicalIndex => {
          this.rowHeightsMap.setValueAtIndex(physicalIndex, null);
        });
      }, true);
    } else {
      this.rowHeightsMap.clear();
    }
  }

  /**
   * Clears cache by range.
   *
   * @param {object|number} range Row index or an object with `from` and `to` properties which define row range.
   */
  clearCacheByRange(range) {
    const {
      from,
      to
    } = typeof range === 'number' ? {
      from: range,
      to: range
    } : range;
    this.hot.batchExecution(() => {
      rangeEach(Math.min(from, to), Math.max(from, to), row => {
        this.rowHeightsMap.setValueAtIndex(row, null);
      });
    }, true);
  }

  /**
   * Checks if all heights were calculated. If not then return `true` (need recalculate).
   *
   * @returns {boolean}
   */
  isNeedRecalculate() {
    return !!this.rowHeightsMap.getValues().slice(0, this.measuredRows).filter(item => item === null).length;
  }

  /**
   * On before view render listener.
   */

  /**
   * Destroys the plugin instance.
   */
  destroy() {
    this.ghostTable.clean();
    super.destroy();
  }
}
function _calculateSpecificRowsHeight(visualRows) {
  const columnsRange = {
    from: 0,
    to: this.hot.countCols() - 1
  };
  visualRows.forEach(visualRow => {
    // For rows we must calculate row height even when user had set height value manually.
    // We can shrink column but cannot shrink rows!
    const samples = this.samplesGenerator.generateRowSamples(visualRow, columnsRange);
    samples.forEach((sample, row) => this.ghostTable.addRow(row, sample));
  });
  if (this.ghostTable.rows.length) {
    this.hot.batchExecution(() => {
      this.ghostTable.getHeights((visualRow, height) => {
        const physicalRow = this.hot.toPhysicalRow(visualRow);
        this.rowHeightsMap.setValueAtIndex(physicalRow, height);
      });
    }, true);
    this.ghostTable.clean();
  }
}
function _onBeforeRender() {
  this.calculateVisibleRowsHeight();
  if (!this.inProgress) {
    _assertClassBrand(_AutoRowSize_brand, this, _calculateSpecificRowsHeight).call(this, _classPrivateFieldGet(_visualRowsToRefresh, this));
    _classPrivateFieldSet(_visualRowsToRefresh, this, []);
  }
}
/**
 * On before row resize listener.
 *
 * @param {number} size The size of the current row index.
 * @param {number} row Current row index.
 * @param {boolean} isDblClick Indicates if the resize was triggered by doubleclick.
 * @returns {number}
 */
function _onBeforeRowResize(size, row, isDblClick) {
  let newSize = size;
  if (isDblClick) {
    this.calculateRowsHeight(row, undefined, true);
    newSize = this.getRowHeight(row);
  }
  return newSize;
}
/**
 * On after load data listener.
 *
 * @param {Array} sourceData Source data.
 * @param {boolean} isFirstLoad `true` if this is the first load.
 */
function _onAfterLoadData(sourceData, isFirstLoad) {
  if (!isFirstLoad) {
    this.recalculateAllRowsHeight();
  }
}
/**
 * On before change listener.
 *
 * @param {Array} changes 2D array containing information about each of the edited cells.
 */
function _onBeforeChange(changes) {
  const changedRows = changes.reduce((acc, _ref) => {
    let [row] = _ref;
    if (acc.indexOf(row) === -1) {
      acc.push(row);
    }
    return acc;
  }, []);
  _classPrivateFieldGet(_visualRowsToRefresh, this).push(...changedRows);
}
/**
 * On after Handsontable init plugin with all necessary values.
 */
function _onInit() {
  this.recalculateAllRowsHeight();
}
/**
 * After formulas values updated listener.
 *
 * @param {Array} changes An array of modified data.
 */
function _onAfterFormulasValuesUpdate(changes) {
  const changedRows = changes.reduce((acc, change) => {
    var _change$address;
    const physicalRow = (_change$address = change.address) === null || _change$address === void 0 ? void 0 : _change$address.row;
    if (Number.isInteger(physicalRow)) {
      const visualRow = this.hot.toVisualRow(physicalRow);
      if (acc.indexOf(visualRow) === -1) {
        acc.push(visualRow);
      }
    }
    return acc;
  }, []);
  _classPrivateFieldGet(_visualRowsToRefresh, this).push(...changedRows);
}