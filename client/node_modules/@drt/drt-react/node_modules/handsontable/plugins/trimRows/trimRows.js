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
var _base = require("../base");
var _translations = require("../../translations");
var _array = require("../../helpers/array");
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
const PLUGIN_KEY = exports.PLUGIN_KEY = 'trimRows';
const PLUGIN_PRIORITY = exports.PLUGIN_PRIORITY = 330;

/* eslint-disable jsdoc/require-description-complete-sentence */

/**
 * @plugin TrimRows
 * @class TrimRows
 *
 * @description
 * The plugin allows to trim certain rows. The trimming is achieved by applying the transformation algorithm to the data
 * transformation. In this case, when the row is trimmed it is not accessible using `getData*` methods thus the trimmed
 * data is not visible to other plugins.
 *
 * @example
 * ::: only-for javascript
 * ```js
 * const container = document.getElementById('example');
 * const hot = new Handsontable(container, {
 *   data: getData(),
 *   // hide selected rows on table initialization
 *   trimRows: [1, 2, 5]
 * });
 *
 * // access the trimRows plugin instance
 * const trimRowsPlugin = hot.getPlugin('trimRows');
 *
 * // hide single row
 * trimRowsPlugin.trimRow(1);
 *
 * // hide multiple rows
 * trimRowsPlugin.trimRow(1, 2, 9);
 *
 * // or as an array
 * trimRowsPlugin.trimRows([1, 2, 9]);
 *
 * // show single row
 * trimRowsPlugin.untrimRow(1);
 *
 * // show multiple rows
 * trimRowsPlugin.untrimRow(1, 2, 9);
 *
 * // or as an array
 * trimRowsPlugin.untrimRows([1, 2, 9]);
 *
 * // rerender table to see the changes
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
 *   // hide selected rows on table initialization
 *   trimRows={[1, 2, 5]}
 * />
 *
 * const hot = hotRef.current.hotInstance;
 * // access the trimRows plugin instance
 * const trimRowsPlugin = hot.getPlugin('trimRows');
 *
 * // hide single row
 * trimRowsPlugin.trimRow(1);
 *
 * // hide multiple rows
 * trimRowsPlugin.trimRow(1, 2, 9);
 *
 * // or as an array
 * trimRowsPlugin.trimRows([1, 2, 9]);
 *
 * // show single row
 * trimRowsPlugin.untrimRow(1);
 *
 * // show multiple rows
 * trimRowsPlugin.untrimRow(1, 2, 9);
 *
 * // or as an array
 * trimRowsPlugin.untrimRows([1, 2, 9]);
 *
 * // rerender table to see the changes
 * hot.render();
 * ```
 * :::
 */
var _TrimRows_brand = /*#__PURE__*/new WeakSet();
class TrimRows extends _base.BasePlugin {
  constructor() {
    super(...arguments);
    /**
     * On map initialized hook callback.
     */
    _classPrivateMethodInitSpec(this, _TrimRows_brand);
    /**
     * Map of skipped rows by the plugin.
     *
     * @private
     * @type {null|TrimmingMap}
     */
    _defineProperty(this, "trimmedRowsMap", null);
  }
  static get PLUGIN_KEY() {
    return PLUGIN_KEY;
  }
  static get PLUGIN_PRIORITY() {
    return PLUGIN_PRIORITY;
  }
  /**
   * Checks if the plugin is enabled in the handsontable settings. This method is executed in {@link Hooks#beforeInit}
   * hook and if it returns `true` then the {@link AutoRowSize#enablePlugin} method is called.
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
    if (this.enabled) {
      return;
    }
    this.trimmedRowsMap = this.hot.rowIndexMapper.registerMap('trimRows', new _translations.TrimmingMap());
    this.trimmedRowsMap.addLocalHook('init', () => _assertClassBrand(_TrimRows_brand, this, _onMapInit).call(this));
    super.enablePlugin();
  }

  /**
   * Updates the plugin's state.
   *
   * This method is executed when [`updateSettings()`](@/api/core.md#updatesettings) is invoked with any of the following configuration options:
   *  - [`trimRows`](@/api/options.md#trimrows)
   */
  updatePlugin() {
    const trimmedRows = this.hot.getSettings()[PLUGIN_KEY];
    if (Array.isArray(trimmedRows)) {
      this.hot.batchExecution(() => {
        this.trimmedRowsMap.clear();
        (0, _array.arrayEach)(trimmedRows, physicalRow => {
          this.trimmedRowsMap.setValueAtIndex(physicalRow, true);
        });
      }, true);
    }
    super.updatePlugin();
  }

  /**
   * Disables the plugin functionality for this Handsontable instance.
   */
  disablePlugin() {
    this.hot.rowIndexMapper.unregisterMap('trimRows');
    super.disablePlugin();
  }

  /**
   * Get list of trimmed rows.
   *
   * @returns {Array} Physical rows.
   */
  getTrimmedRows() {
    return this.trimmedRowsMap.getTrimmedIndexes();
  }

  /**
   * Trims the rows provided in the array.
   *
   * @param {number[]} rows Array of physical row indexes.
   * @fires Hooks#beforeTrimRow
   * @fires Hooks#afterTrimRow
   */
  trimRows(rows) {
    const currentTrimConfig = this.getTrimmedRows();
    const isValidConfig = this.isValidConfig(rows);
    let destinationTrimConfig = currentTrimConfig;
    if (isValidConfig) {
      destinationTrimConfig = Array.from(new Set(currentTrimConfig.concat(rows)));
    }
    const allowTrimRow = this.hot.runHooks('beforeTrimRow', currentTrimConfig, destinationTrimConfig, isValidConfig);
    if (allowTrimRow === false) {
      return;
    }
    if (isValidConfig) {
      this.hot.batchExecution(() => {
        (0, _array.arrayEach)(rows, physicalRow => {
          this.trimmedRowsMap.setValueAtIndex(physicalRow, true);
        });
      }, true);
    }
    this.hot.runHooks('afterTrimRow', currentTrimConfig, destinationTrimConfig, isValidConfig, isValidConfig && destinationTrimConfig.length > currentTrimConfig.length);
  }

  /**
   * Trims the row provided as a physical row index (counting from 0).
   *
   * @param {...number} row Physical row index.
   */
  trimRow() {
    for (var _len = arguments.length, row = new Array(_len), _key = 0; _key < _len; _key++) {
      row[_key] = arguments[_key];
    }
    this.trimRows(row);
  }

  /**
   * Untrims the rows provided in the array.
   *
   * @param {number[]} rows Array of physical row indexes.
   * @fires Hooks#beforeUntrimRow
   * @fires Hooks#afterUntrimRow
   */
  untrimRows(rows) {
    const currentTrimConfig = this.getTrimmedRows();
    const isValidConfig = this.isValidConfig(rows);
    let destinationTrimConfig = currentTrimConfig;
    const trimmingMapValues = this.trimmedRowsMap.getValues().slice();
    const isAnyRowUntrimmed = rows.length > 0;
    if (isValidConfig && isAnyRowUntrimmed) {
      // Preparing new values for trimming map.
      (0, _array.arrayEach)(rows, physicalRow => {
        trimmingMapValues[physicalRow] = false;
      });

      // Preparing new trimming config.
      destinationTrimConfig = (0, _array.arrayReduce)(trimmingMapValues, (trimmedIndexes, isTrimmed, physicalIndex) => {
        if (isTrimmed) {
          trimmedIndexes.push(physicalIndex);
        }
        return trimmedIndexes;
      }, []);
    }
    const allowUntrimRow = this.hot.runHooks('beforeUntrimRow', currentTrimConfig, destinationTrimConfig, isValidConfig && isAnyRowUntrimmed);
    if (allowUntrimRow === false) {
      return;
    }
    if (isValidConfig && isAnyRowUntrimmed) {
      this.trimmedRowsMap.setValues(trimmingMapValues);
    }
    this.hot.runHooks('afterUntrimRow', currentTrimConfig, destinationTrimConfig, isValidConfig && isAnyRowUntrimmed, isValidConfig && destinationTrimConfig.length < currentTrimConfig.length);
  }

  /**
   * Untrims the row provided as a physical row index (counting from 0).
   *
   * @param {...number} row Physical row index.
   */
  untrimRow() {
    for (var _len2 = arguments.length, row = new Array(_len2), _key2 = 0; _key2 < _len2; _key2++) {
      row[_key2] = arguments[_key2];
    }
    this.untrimRows(row);
  }

  /**
   * Checks if given row is hidden.
   *
   * @param {number} physicalRow Physical row index.
   * @returns {boolean}
   */
  isTrimmed(physicalRow) {
    return this.trimmedRowsMap.getValueAtIndex(physicalRow) || false;
  }

  /**
   * Untrims all trimmed rows.
   */
  untrimAll() {
    this.untrimRows(this.getTrimmedRows());
  }

  /**
   * Get if trim config is valid. Check whether all of the provided physical row indexes are within source data.
   *
   * @param {Array} trimmedRows List of physical row indexes.
   * @returns {boolean}
   */
  isValidConfig(trimmedRows) {
    const sourceRows = this.hot.countSourceRows();
    return trimmedRows.every(trimmedRow => Number.isInteger(trimmedRow) && trimmedRow >= 0 && trimmedRow < sourceRows);
  }
  /**
   * Destroys the plugin instance.
   */
  destroy() {
    super.destroy();
  }
}
exports.TrimRows = TrimRows;
function _onMapInit() {
  const trimmedRows = this.hot.getSettings()[PLUGIN_KEY];
  if (Array.isArray(trimmedRows)) {
    this.hot.batchExecution(() => {
      (0, _array.arrayEach)(trimmedRows, physicalRow => {
        this.trimmedRowsMap.setValueAtIndex(physicalRow, true);
      });
    }, true);
  }
}