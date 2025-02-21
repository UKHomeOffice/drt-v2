"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
require("core-js/modules/esnext.iterator.constructor.js");
require("core-js/modules/esnext.iterator.for-each.js");
var _base = require("../base");
var _calculator = require("./calculator");
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
const PLUGIN_KEY = exports.PLUGIN_KEY = 'stretchColumns';
const PLUGIN_PRIORITY = exports.PLUGIN_PRIORITY = 155;

/* eslint-disable jsdoc/require-description-complete-sentence */
/**
 * @plugin StretchColumns
 * @class StretchColumns
 *
 * @description
 * This plugin allows to set column widths based on their widest cells.
 *
 * By default, the plugin is declared as `'none'`, which makes it disabled (same as if it was declared as `false`).
 *
 * The plugin determines what happens when the declared grid width is different from the calculated sum of all column widths.
 *
 * ```js
 * // fit the grid to the container, by stretching only the last column
 * stretchH: 'last',
 *
 * // fit the grid to the container, by stretching all columns evenly
 * stretchH: 'all',
 * ```
 *
 * To configure this plugin see {@link Options#stretchH}.
 *
 * @example
 *
 * ::: only-for javascript
 * ```js
 * const hot = new Handsontable(document.getElementById('example'), {
 *   data: getData(),
 *   stretchH: 'all',
 * });
 * ```
 * :::
 *
 * ::: only-for react
 * ```jsx
 * const hotRef = useRef(null);
 *
 * ...
 *
 * // First, let's construct Handsontable
 * <HotTable
 *   ref={hotRef}
 *   data={getData()}
 *   stretchH={'all'}
 * />
 * ```
 * :::
 */
/* eslint-enable jsdoc/require-description-complete-sentence */
var _stretchCalculator = /*#__PURE__*/new WeakMap();
var _previousTableWidth = /*#__PURE__*/new WeakMap();
var _resizeObserver = /*#__PURE__*/new WeakMap();
var _StretchColumns_brand = /*#__PURE__*/new WeakSet();
class StretchColumns extends _base.BasePlugin {
  constructor() {
    super(...arguments);
    /**
     * Hook that modifies the column width - applies by the stretching logic.
     *
     * @param {number} width The column width.
     * @param {number} column The visual column index.
     * @param {string} source The source of the modification.
     * @returns {number}
     */
    _classPrivateMethodInitSpec(this, _StretchColumns_brand);
    /**
     * The stretch calculator.
     *
     * @type {StretchCalculator}
     */
    _classPrivateFieldInitSpec(this, _stretchCalculator, new _calculator.StretchCalculator(this.hot));
    /**
     * The previous width of the root element. Helps to determine if the width has changed.
     *
     * @type {number | null}
     */
    _classPrivateFieldInitSpec(this, _previousTableWidth, null);
    /**
     * It observes the root element to detect changes in its width, and if detected, then it triggers
     * the table dimension calculations. In a situation where the browser's vertical scrollbar
     * appears - caused by some external UI element, the observer triggers the render.
     *
     * @type {ResizeObserver}
     */
    _classPrivateFieldInitSpec(this, _resizeObserver, new ResizeObserver(entries => {
      requestAnimationFrame(() => {
        var _this$hot;
        if (!((_this$hot = this.hot) !== null && _this$hot !== void 0 && _this$hot.view.isHorizontallyScrollableByWindow())) {
          return;
        }
        entries.forEach(_ref => {
          let {
            contentRect
          } = _ref;
          if (_classPrivateFieldGet(_previousTableWidth, this) !== null && _classPrivateFieldGet(_previousTableWidth, this) !== contentRect.width) {
            this.hot.refreshDimensions();
            this.hot.view.adjustElementsSize();
          }
          _classPrivateFieldSet(_previousTableWidth, this, contentRect.width);
        });
      });
    }));
  }
  static get PLUGIN_KEY() {
    return PLUGIN_KEY;
  }
  static get PLUGIN_PRIORITY() {
    return PLUGIN_PRIORITY;
  }
  static get SETTING_KEYS() {
    return true;
  }
  /**
   * Checks if the plugin is enabled in the handsontable settings. This method is executed in {@link Hooks#beforeInit}
   * hook and if it returns `true` then the {@link #enablePlugin} method is called.
   *
   * @returns {boolean}
   */
  isEnabled() {
    return ['all', 'last'].includes(this.hot.getSettings().stretchH);
  }

  /**
   * Enables the plugin functionality for this Handsontable instance.
   */
  enablePlugin() {
    var _this = this;
    if (this.enabled) {
      return;
    }
    _classPrivateFieldGet(_stretchCalculator, this).useStrategy(this.hot.getSettings().stretchH);
    _classPrivateFieldGet(_resizeObserver, this).observe(this.hot.rootElement);
    this.addHook('beforeRender', function () {
      for (var _len = arguments.length, args = new Array(_len), _key = 0; _key < _len; _key++) {
        args[_key] = arguments[_key];
      }
      return _assertClassBrand(_StretchColumns_brand, _this, _onBeforeRender).call(_this, ...args);
    });
    this.addHook('modifyColWidth', function () {
      for (var _len2 = arguments.length, args = new Array(_len2), _key2 = 0; _key2 < _len2; _key2++) {
        args[_key2] = arguments[_key2];
      }
      return _assertClassBrand(_StretchColumns_brand, _this, _onModifyColWidth).call(_this, ...args);
    }, 10);
    super.enablePlugin();
  }

  /**
   * Updates the plugin's state. This method is executed when {@link Core#updateSettings} is invoked.
   */
  updatePlugin() {
    _classPrivateFieldGet(_stretchCalculator, this).useStrategy(this.hot.getSettings().stretchH);
    super.updatePlugin();
  }

  /**
   * Disables the plugin functionality for this Handsontable instance.
   */
  disablePlugin() {
    super.disablePlugin();
    _classPrivateFieldGet(_resizeObserver, this).unobserve(this.hot.rootElement);
  }

  /**
   * Gets the calculated column width based on the stretching
   * strategy defined by {@link Options#stretchH} option.
   *
   * @param {number} columnVisualIndex The visual index of the column.
   * @returns {number | null}
   */
  getColumnWidth(columnVisualIndex) {
    return _classPrivateFieldGet(_stretchCalculator, this).getStretchedWidth(columnVisualIndex);
  }
  /**
   * Destroys the plugin instance.
   */
  destroy() {
    _classPrivateFieldGet(_resizeObserver, this).disconnect();
    _classPrivateFieldSet(_resizeObserver, this, null);
    _classPrivateFieldSet(_stretchCalculator, this, null);
    super.destroy();
  }
}
exports.StretchColumns = StretchColumns;
function _onModifyColWidth(width, column, source) {
  if (source === this.pluginName) {
    return;
  }
  const newWidth = this.getColumnWidth(column);
  if (typeof newWidth === 'number') {
    return newWidth;
  }
  return width;
}
/**
 * On each before render the plugin recalculates the column widths
 * based on the chosen stretching strategy.
 *
 * @param {boolean} fullRender If `true` then the full render is in progress.
 */
function _onBeforeRender(fullRender) {
  if (fullRender) {
    _classPrivateFieldGet(_stretchCalculator, this).refreshStretching();
  }
}