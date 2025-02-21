"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
var _base = require("../base");
var _looseBindsMap = _interopRequireDefault(require("./maps/looseBindsMap"));
var _strictBindsMap = _interopRequireDefault(require("./maps/strictBindsMap"));
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
const PLUGIN_KEY = exports.PLUGIN_KEY = 'bindRowsWithHeaders';
const PLUGIN_PRIORITY = exports.PLUGIN_PRIORITY = 210;
const DEFAULT_BIND = 'loose';
const bindTypeToMapStrategy = new Map([['loose', _looseBindsMap.default], ['strict', _strictBindsMap.default]]);

/**
 * @plugin BindRowsWithHeaders
 * @class BindRowsWithHeaders
 *
 * @description
 * Plugin allows binding the table rows with their headers.
 *
 * If the plugin is enabled, the table row headers will "stick" to the rows, when they are hidden/moved. Basically, if
 * at the initialization row 0 has a header titled "A", it will have it no matter what you do with the table.
 *
 * @example
 * ::: only-for javascript
 * ```js
 * const container = document.getElementById('example');
 * const hot = new Handsontable(container, {
 *   data: getData(),
 *   // enable plugin
 *   bindRowsWithHeaders: true
 * });
 * ```
 * :::
 *
 * ::: only-for react
 * ```jsx
 * <HotTable
 *   data={getData()}
 *   // enable plugin
 *   bindRowsWithHeaders={true}
 * />
 * ```
 * :::
 */
var _BindRowsWithHeaders_brand = /*#__PURE__*/new WeakSet();
class BindRowsWithHeaders extends _base.BasePlugin {
  constructor() {
    super(...arguments);
    /**
     * On modify row header listener.
     *
     * @param {number} row Row index.
     * @returns {number}
     */
    _classPrivateMethodInitSpec(this, _BindRowsWithHeaders_brand);
    /**
     * Plugin indexes cache.
     *
     * @private
     * @type {null|IndexMap}
     */
    _defineProperty(this, "headerIndexes", null);
  }
  static get PLUGIN_KEY() {
    return PLUGIN_KEY;
  }
  static get PLUGIN_PRIORITY() {
    return PLUGIN_PRIORITY;
  }
  /**
   * Checks if the plugin is enabled in the handsontable settings. This method is executed in {@link Hooks#beforeInit}
   * hook and if it returns `true` then the {@link BindRowsWithHeaders#enablePlugin} method is called.
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
    var _bindTypeToMapStrateg;
    if (this.enabled) {
      return;
    }
    const MapStrategy = (_bindTypeToMapStrateg = bindTypeToMapStrategy.get(this.getSetting())) !== null && _bindTypeToMapStrateg !== void 0 ? _bindTypeToMapStrateg : bindTypeToMapStrategy.get(DEFAULT_BIND);
    this.headerIndexes = this.hot.rowIndexMapper.registerMap('bindRowsWithHeaders', new MapStrategy());
    this.addHook('modifyRowHeader', row => _assertClassBrand(_BindRowsWithHeaders_brand, this, _onModifyRowHeader).call(this, row));
    super.enablePlugin();
  }

  /**
   * Disables the plugin functionality for this Handsontable instance.
   */
  disablePlugin() {
    this.hot.rowIndexMapper.unregisterMap('bindRowsWithHeaders');
    super.disablePlugin();
  }
  /**
   * Destroys the plugin instance.
   */
  destroy() {
    super.destroy();
  }
}
exports.BindRowsWithHeaders = BindRowsWithHeaders;
function _onModifyRowHeader(row) {
  return this.headerIndexes.getValueAtIndex(this.hot.toPhysicalRow(row));
}