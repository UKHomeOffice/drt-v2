"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
require("core-js/modules/es.array.push.js");
require("core-js/modules/esnext.iterator.constructor.js");
require("core-js/modules/esnext.iterator.some.js");
var _base = require("../base");
var _hooks = require("../../core/hooks");
var _freezeColumn = _interopRequireDefault(require("./contextMenuItem/freezeColumn"));
var _unfreezeColumn = _interopRequireDefault(require("./contextMenuItem/unfreezeColumn"));
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
_hooks.Hooks.getSingleton().register('beforeColumnFreeze');
_hooks.Hooks.getSingleton().register('afterColumnFreeze');
_hooks.Hooks.getSingleton().register('beforeColumnUnfreeze');
_hooks.Hooks.getSingleton().register('afterColumnUnfreeze');
const PLUGIN_KEY = exports.PLUGIN_KEY = 'manualColumnFreeze';
const PLUGIN_PRIORITY = exports.PLUGIN_PRIORITY = 110;

/* eslint-disable jsdoc/require-description-complete-sentence */

/**
 * @plugin ManualColumnFreeze
 * @class ManualColumnFreeze
 *
 * @description
 * This plugin allows to manually "freeze" and "unfreeze" a column using an entry in the Context Menu or using API.
 * You can turn it on by setting a {@link Options#manualColumnFreeze} property to `true`.
 *
 * @example
 * ```js
 * // Enables the plugin
 * manualColumnFreeze: true,
 * ```
 */
var _afterFirstUse = /*#__PURE__*/new WeakMap();
var _ManualColumnFreeze_brand = /*#__PURE__*/new WeakSet();
class ManualColumnFreeze extends _base.BasePlugin {
  constructor() {
    super(...arguments);
    /**
     * Adds the manualColumnFreeze context menu entries.
     *
     * @private
     * @param {object} options Context menu options.
     */
    _classPrivateMethodInitSpec(this, _ManualColumnFreeze_brand);
    /**
     * Determines when the moving operation is allowed.
     *
     * @type {boolean}
     */
    _classPrivateFieldInitSpec(this, _afterFirstUse, false);
  }
  static get PLUGIN_KEY() {
    return PLUGIN_KEY;
  }
  static get PLUGIN_PRIORITY() {
    return PLUGIN_PRIORITY;
  }
  /**
   * Checks if the plugin is enabled in the handsontable settings. This method is executed in {@link Hooks#beforeInit}
   * hook and if it returns `true` then the {@link ManualColumnFreeze#enablePlugin} method is called.
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
    this.addHook('afterContextMenuDefaultOptions', options => _assertClassBrand(_ManualColumnFreeze_brand, this, _addContextMenuEntry).call(this, options));
    this.addHook('beforeColumnMove', (columns, finalIndex) => _assertClassBrand(_ManualColumnFreeze_brand, this, _onBeforeColumnMove).call(this, columns, finalIndex));
    super.enablePlugin();
  }

  /**
   * Disables the plugin functionality for this Handsontable instance.
   */
  disablePlugin() {
    _classPrivateFieldSet(_afterFirstUse, this, false);
    super.disablePlugin();
  }

  /**
   * Updates the plugin's state.
   *
   * This method is executed when [`updateSettings()`](@/api/core.md#updatesettings) is invoked with any of the following configuration options:
   *  - [`manualColumnFreeze`](@/api/options.md#manualcolumnfreeze)
   */
  updatePlugin() {
    this.disablePlugin();
    this.enablePlugin();
    super.updatePlugin();
  }

  /**
   * Freezes the specified column (adds it to fixed columns).
   *
   * `freezeColumn()` doesn't re-render the table,
   * so you need to call the `render()` method afterward.
   *
   * @param {number} column Visual column index.
   */
  freezeColumn(column) {
    const settings = this.hot.getSettings();
    // columns are already fixed (frozen)
    const freezePerformed = settings.fixedColumnsStart < this.hot.countCols() && column > settings.fixedColumnsStart - 1;
    if (!_classPrivateFieldGet(_afterFirstUse, this)) {
      _classPrivateFieldSet(_afterFirstUse, this, true);
    }
    const beforeColumnFreezeHook = this.hot.runHooks('beforeColumnFreeze', column, freezePerformed);
    if (beforeColumnFreezeHook === false) {
      return;
    }
    if (freezePerformed) {
      this.hot.columnIndexMapper.moveIndexes(column, settings.fixedColumnsStart);

      // Since 12.0.0, the "fixedColumnsLeft" is replaced with the "fixedColumnsStart" option.
      // However, keeping the old name still in effect. When both option names are used together,
      // the error is thrown. To prevent that, the plugin needs to modify the original option key
      // to bypass the validation.
      settings._fixedColumnsStart += 1;
    }
    this.hot.runHooks('afterColumnFreeze', column, freezePerformed);
  }

  /**
   * Unfreezes the given column (remove it from fixed columns and bring to it's previous position).
   *
   * @param {number} column Visual column index.
   */
  unfreezeColumn(column) {
    const settings = this.hot.getSettings();
    // columns are not fixed (not frozen)
    const unfreezePerformed = settings.fixedColumnsStart > 0 && column <= settings.fixedColumnsStart - 1;
    if (!_classPrivateFieldGet(_afterFirstUse, this)) {
      _classPrivateFieldSet(_afterFirstUse, this, true);
    }
    const beforeColumnUnfreezeHook = this.hot.runHooks('beforeColumnUnfreeze', column, unfreezePerformed);
    if (beforeColumnUnfreezeHook === false) {
      return;
    }
    if (unfreezePerformed) {
      // Since 12.0.0, the "fixedColumnsLeft" is replaced with the "fixedColumnsStart" option.
      // However, keeping the old name still in effect. When both option names are used together,
      // the error is thrown. To prevent that, the plugin needs to modify the original option key
      // to bypass the validation.
      settings._fixedColumnsStart -= 1;
      this.hot.columnIndexMapper.moveIndexes(column, settings.fixedColumnsStart);
    }
    this.hot.runHooks('afterColumnUnfreeze', column, unfreezePerformed);
  }
}
exports.ManualColumnFreeze = ManualColumnFreeze;
function _addContextMenuEntry(options) {
  options.items.push({
    name: '---------'
  }, (0, _freezeColumn.default)(this), (0, _unfreezeColumn.default)(this));
}
/**
 * Prevents moving the columns from/to fixed area.
 *
 * @private
 * @param {Array} columns Array of visual column indexes to be moved.
 * @param {number} finalIndex Visual column index, being a start index for the moved columns. Points to where the elements will be placed after the moving action.
 * @returns {boolean|undefined}
 */
function _onBeforeColumnMove(columns, finalIndex) {
  if (_classPrivateFieldGet(_afterFirstUse, this)) {
    const freezeLine = this.hot.getSettings().fixedColumnsStart;

    // Moving any column before the "freeze line" isn't possible.
    if (finalIndex < freezeLine) {
      return false;
    }

    // Moving frozen column isn't possible.
    if (columns.some(column => column < freezeLine)) {
      return false;
    }
  }
}