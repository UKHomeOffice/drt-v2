"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
require("core-js/modules/es.array.push.js");
require("core-js/modules/esnext.iterator.constructor.js");
require("core-js/modules/esnext.iterator.for-each.js");
var _object = require("../../helpers/object");
var _array = require("../../helpers/array");
var _registry = require("../registry");
var _registry2 = require("../../cellTypes/registry");
var _registry3 = require("../../editors/registry");
var _registry4 = require("../../renderers/registry");
var _registry5 = require("../../validators/registry");
var _eventManager = _interopRequireDefault(require("../../eventManager"));
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
const DEPS_TYPE_CHECKERS = new Map([['plugin', _registry.hasPlugin], ['cell-type', _registry2.hasCellType], ['editor', _registry3.hasEditor], ['renderer', _registry4.hasRenderer], ['validator', _registry5.hasValidator]]);
const defaultMainSettingSymbol = exports.defaultMainSettingSymbol = Symbol('mainSetting');
const PLUGIN_KEY = exports.PLUGIN_KEY = 'base';
const missingDepsMsgs = [];
let initializedPlugins = null;

/**
 * @util
 * @property {Core} hot Handsontable instance.
 */
var _hooks = /*#__PURE__*/new WeakMap();
var _BasePlugin_brand = /*#__PURE__*/new WeakSet();
class BasePlugin {
  static get PLUGIN_KEY() {
    return PLUGIN_KEY;
  }

  /**
   * The `SETTING_KEYS` getter defines the keys that, when present in the config object, trigger the plugin update
   * after the `updateSettings` calls.
   * - When it returns `true`, the plugin updates after all `updateSettings` calls, regardless of the contents of the
   * config object.
   * - When it returns `false`, the plugin never updates on `updateSettings` calls.
   *
   * @returns {string[] | boolean}
   */
  static get SETTING_KEYS() {
    return [this.PLUGIN_KEY];
  }

  /**
   * The `DEFAULT_SETTINGS` getter defines the plugin default settings.
   *
   * @returns {object}
   */
  static get DEFAULT_SETTINGS() {
    return {};
  }

  /**
   * The instance of the {@link EventManager} class.
   *
   * @type {EventManager}
   */

  /**
   * @param {object} hotInstance Handsontable instance.
   */
  constructor(hotInstance) {
    /**
     * Check if any of the keys defined in `SETTING_KEYS` configuration of the plugin is present in the provided
     * config object, or if the `SETTING_KEYS` configuration states that the plugin is relevant to the config object
     * regardless of its contents.
     *
     * @private
     * @param {Handsontable.DefaultSettings} settings The config object passed to `updateSettings`.
     * @returns {boolean}
     */
    _classPrivateMethodInitSpec(this, _BasePlugin_brand);
    _defineProperty(this, "eventManager", new _eventManager.default(this));
    /**
     * @type {string}
     */
    _defineProperty(this, "pluginName", null);
    /**
     * @type {Function[]}
     */
    _defineProperty(this, "pluginsInitializedCallbacks", []);
    /**
     * @type {boolean}
     */
    _defineProperty(this, "isPluginsReady", false);
    /**
     * @type {boolean}
     */
    _defineProperty(this, "enabled", false);
    /**
     * @type {boolean}
     */
    _defineProperty(this, "initialized", false);
    /**
     * Collection of the reference to the plugins hooks.
     */
    _classPrivateFieldInitSpec(this, _hooks, {});
    /**
     * Handsontable instance.
     *
     * @type {Core}
     */
    (0, _object.defineGetter)(this, 'hot', hotInstance, {
      writable: false
    });
    initializedPlugins = null;
    this.hot.addHook('afterPluginsInitialized', () => this.onAfterPluginsInitialized());
    this.hot.addHook('afterUpdateSettings', newSettings => this.onUpdateSettings(newSettings));
    this.hot.addHook('beforeInit', () => this.init());
  }
  init() {
    this.pluginName = this.hot.getPluginName(this);
    const pluginDeps = this.constructor.PLUGIN_DEPS;
    const deps = Array.isArray(pluginDeps) ? pluginDeps : [];
    if (deps.length > 0) {
      const missingDependencies = [];
      deps.forEach(dependency => {
        const [type, moduleName] = dependency.split(':');
        if (!DEPS_TYPE_CHECKERS.has(type)) {
          throw new Error(`Unknown plugin dependency type "${type}" was found.`);
        }
        if (!DEPS_TYPE_CHECKERS.get(type)(moduleName)) {
          missingDependencies.push(` - ${moduleName} (${type})`);
        }
      });
      if (missingDependencies.length > 0) {
        const errorMsg = [`The ${this.pluginName} plugin requires the following modules:\n`, `${missingDependencies.join('\n')}\n`].join('');
        missingDepsMsgs.push(errorMsg);
      }
    }
    if (!initializedPlugins) {
      initializedPlugins = (0, _registry.getPluginsNames)();
    }
    if (initializedPlugins.indexOf(this.pluginName) >= 0) {
      initializedPlugins.splice(initializedPlugins.indexOf(this.pluginName), 1);
    }
    this.hot.addHookOnce('afterPluginsInitialized', () => {
      if (this.isEnabled && this.isEnabled()) {
        this.enablePlugin();
      }
    });
    const isAllPluginsAreInitialized = initializedPlugins.length === 0;
    if (isAllPluginsAreInitialized) {
      if (missingDepsMsgs.length > 0) {
        const errorMsg = [`${missingDepsMsgs.join('\n')}\n`, 'You have to import and register them manually.'].join('');
        missingDepsMsgs.length = 0;
        throw new Error(errorMsg);
      }
      this.hot.runHooks('afterPluginsInitialized');
    }
    this.initialized = true;
  }

  /**
   * Enable plugin for this Handsontable instance.
   */
  enablePlugin() {
    this.enabled = true;
  }

  /**
   * Disable plugin for this Handsontable instance.
   */
  disablePlugin() {
    var _this$eventManager;
    (_this$eventManager = this.eventManager) === null || _this$eventManager === void 0 || _this$eventManager.clear();
    this.clearHooks();
    this.enabled = false;
  }

  /**
   * Gets the plugin settings. If there is no setting under the provided key, it returns the default setting
   * provided by the DEFAULT_SETTINGS static property of the class.
   *
   * @param {string} [settingName] The setting name. If the setting name is not provided, it returns
   * the whole plugin's settings object.
   * @returns {*}
   */
  getSetting(settingName) {
    const pluginSettings = this.hot.getSettings()[this.constructor.PLUGIN_KEY];
    if (settingName === undefined) {
      return pluginSettings;
    }
    const defaultSettings = this.constructor.DEFAULT_SETTINGS;
    if ((Array.isArray(pluginSettings) || (0, _object.isObject)(pluginSettings)) && defaultSettings[defaultMainSettingSymbol] === settingName) {
      var _pluginSettings$setti;
      if (Array.isArray(pluginSettings)) {
        return pluginSettings;
      }
      return (_pluginSettings$setti = pluginSettings[settingName]) !== null && _pluginSettings$setti !== void 0 ? _pluginSettings$setti : defaultSettings[settingName];
    }
    if ((0, _object.isObject)(pluginSettings)) {
      var _pluginSettings$setti2;
      return (_pluginSettings$setti2 = pluginSettings[settingName]) !== null && _pluginSettings$setti2 !== void 0 ? _pluginSettings$setti2 : defaultSettings[settingName];
    }
    return defaultSettings[settingName];
  }

  /**
   * Add listener to plugin hooks system.
   *
   * @param {string} name The hook name.
   * @param {Function} callback The listener function to add.
   * @param {number} [orderIndex] Order index of the callback.
   *                              If > 0, the callback will be added after the others, for example, with an index of 1, the callback will be added before the ones with an index of 2, 3, etc., but after the ones with an index of 0 and lower.
   *                              If < 0, the callback will be added before the others, for example, with an index of -1, the callback will be added after the ones with an index of -2, -3, etc., but before the ones with an index of 0 and higher.
   *                              If 0 or no order index is provided, the callback will be added between the "negative" and "positive" indexes.
   */
  addHook(name, callback, orderIndex) {
    _classPrivateFieldGet(_hooks, this)[name] = _classPrivateFieldGet(_hooks, this)[name] || [];
    const hooks = _classPrivateFieldGet(_hooks, this)[name];
    this.hot.addHook(name, callback, orderIndex);
    hooks.push(callback);
    _classPrivateFieldGet(_hooks, this)[name] = hooks;
  }

  /**
   * Remove all hooks listeners by hook name.
   *
   * @param {string} name The hook name.
   */
  removeHooks(name) {
    (0, _array.arrayEach)(_classPrivateFieldGet(_hooks, this)[name] || [], callback => {
      this.hot.removeHook(name, callback);
    });
  }

  /**
   * Clear all hooks.
   */
  clearHooks() {
    const hooks = _classPrivateFieldGet(_hooks, this);
    (0, _object.objectEach)(hooks, (callbacks, name) => this.removeHooks(name));
    hooks.length = 0;
  }

  /**
   * Register function which will be immediately called after all plugins initialized.
   *
   * @param {Function} callback The listener function to call.
   */
  callOnPluginsReady(callback) {
    if (this.isPluginsReady) {
      callback();
    } else {
      this.pluginsInitializedCallbacks.push(callback);
    }
  }
  /**
   * On after plugins initialized listener.
   *
   * @private
   */
  onAfterPluginsInitialized() {
    (0, _array.arrayEach)(this.pluginsInitializedCallbacks, callback => callback());
    this.pluginsInitializedCallbacks.length = 0;
    this.isPluginsReady = true;
  }

  /**
   * On update settings listener.
   *
   * @private
   * @param {object} newSettings New set of settings passed to the `updateSettings` method.
   */
  onUpdateSettings(newSettings) {
    const relevantToSettings = _assertClassBrand(_BasePlugin_brand, this, _isRelevantToSettings).call(this, newSettings);
    if (this.isEnabled) {
      if (this.enabled && !this.isEnabled()) {
        this.disablePlugin();
      }
      if (!this.enabled && this.isEnabled()) {
        this.enablePlugin();
      }
      if (this.enabled && this.isEnabled() && relevantToSettings) {
        this.updatePlugin(newSettings);
      }
    }
  }

  /**
   * Updates the plugin to use the latest options you have specified.
   *
   * @private
   */
  updatePlugin() {}

  /**
   * Destroy plugin.
   */
  destroy() {
    var _this$eventManager2;
    (_this$eventManager2 = this.eventManager) === null || _this$eventManager2 === void 0 || _this$eventManager2.destroy();
    this.clearHooks();
    (0, _object.objectEach)(this, (value, property) => {
      if (property !== 'hot') {
        this[property] = null;
      }
    });
    delete this.t;
    delete this.hot;
  }
}
exports.BasePlugin = BasePlugin;
function _isRelevantToSettings(settings) {
  if (!settings) {
    return false;
  }
  const settingKeys = this.constructor.SETTING_KEYS;

  // If SETTING_KEYS is declared as `true` -> update the plugin regardless of the settings declared in
  // `updateSettings`.
  // If SETTING_KEYS is declared as `false` -> DON'T update the plugin regardless of the settings declared in
  // `updateSettings`.
  if (typeof settingKeys === 'boolean') {
    return settingKeys;
  }
  for (let i = 0; i < settingKeys.length; i++) {
    if (settings[settingKeys[i]] !== undefined) {
      return true;
    }
  }
  return false;
}