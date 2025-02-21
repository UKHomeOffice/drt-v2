"use strict";

require("core-js/modules/es.error.cause.js");
exports.__esModule = true;
require("core-js/modules/es.array.push.js");
require("core-js/modules/es.set.difference.v2.js");
require("core-js/modules/es.set.intersection.v2.js");
require("core-js/modules/es.set.is-disjoint-from.v2.js");
require("core-js/modules/es.set.is-subset-of.v2.js");
require("core-js/modules/es.set.is-superset-of.v2.js");
require("core-js/modules/es.set.symmetric-difference.v2.js");
require("core-js/modules/es.set.union.v2.js");
var _base = require("../base");
var _hooks = require("../../core/hooks");
var _object = require("../../helpers/object");
var _templateLiteralTag = require("../../helpers/templateLiteralTag");
var _console = require("../../helpers/console");
var _actions = require("./actions");
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
const SHORTCUTS_GROUP = 'undoRedo';
const PLUGIN_KEY = exports.PLUGIN_KEY = 'undoRedo';
const PLUGIN_PRIORITY = exports.PLUGIN_PRIORITY = 1000;
_hooks.Hooks.getSingleton().register('beforeUndo');
_hooks.Hooks.getSingleton().register('afterUndo');
_hooks.Hooks.getSingleton().register('beforeRedo');
_hooks.Hooks.getSingleton().register('afterRedo');
const deprecationWarns = new Set();

/**
 * @description
 * Handsontable UndoRedo plugin allows to undo and redo certain actions done in the table.
 *
 * __Note__, that not all actions are currently undo-able. The UndoRedo plugin is enabled by default.
 * @example
 * ```js
 * undo: true
 * ```
 * @class UndoRedo
 * @plugin UndoRedo
 */
var _UndoRedo_brand = /*#__PURE__*/new WeakSet();
class UndoRedo extends _base.BasePlugin {
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
   * The list of registered action do undo.
   *
   * @private
   * @type {Array}
   */

  constructor(hotInstance) {
    super(hotInstance);
    /**
     * Listens to the data change and if the source is `loadData` then clears the undo and redo history.
     *
     * @param {Array} changes The data changes.
     * @param {string} source The source of the change.
     */
    _classPrivateMethodInitSpec(this, _UndoRedo_brand);
    _defineProperty(this, "doneActions", []);
    /**
     * The list of registered action do redo.
     *
     * @private
     * @type {Array}
     */
    _defineProperty(this, "undoneActions", []);
    /**
     * The flag that determines if new actions should be ignored.
     *
     * @private
     * @type {boolean}
     */
    _defineProperty(this, "ignoreNewActions", false);
    (0, _actions.registerActions)(hotInstance, this);
  }

  /**
   * Checks if the plugin is enabled in the handsontable settings. This method is executed in {@link Hooks#beforeInit}
   * hook and if it returns `true` then the {@link UndoRedo#enablePlugin} method is called.
   *
   * @returns {boolean}
   */
  isEnabled() {
    return !!this.hot.getSettings().undo;
  }

  /**
   * Enables the plugin functionality for this Handsontable instance.
   */
  enablePlugin() {
    var _this = this;
    if (this.enabled) {
      return;
    }
    this.addHook('afterChange', function () {
      for (var _len = arguments.length, args = new Array(_len), _key = 0; _key < _len; _key++) {
        args[_key] = arguments[_key];
      }
      return _assertClassBrand(_UndoRedo_brand, _this, _onAfterChange).call(_this, ...args);
    });
    this.registerShortcuts();
    _assertClassBrand(_UndoRedo_brand, this, _exposeAPIToCore).call(this);
    super.enablePlugin();
  }

  /**
   * Disables the plugin functionality for this Handsontable instance.
   */
  disablePlugin() {
    super.disablePlugin();
    this.clear();
    this.unregisterShortcuts();
    _assertClassBrand(_UndoRedo_brand, this, _removeAPIFromCore).call(this);
  }

  /**
   * Registers shortcuts responsible for performing undo/redo.
   *
   * @private
   */
  registerShortcuts() {
    const shortcutManager = this.hot.getShortcutManager();
    const gridContext = shortcutManager.getContext('grid');
    const runOnlyIf = event => {
      return !event.altKey; // right ALT in some systems triggers ALT+CTR
    };
    const config = {
      runOnlyIf,
      group: SHORTCUTS_GROUP
    };
    gridContext.addShortcuts([{
      keys: [['Control/Meta', 'z']],
      callback: () => {
        this.undo();
      }
    }, {
      keys: [['Control/Meta', 'y'], ['Control/Meta', 'Shift', 'z']],
      callback: () => {
        this.redo();
      }
    }], config);
  }

  /**
   * Unregister shortcuts responsible for performing undo/redo.
   *
   * @private
   */
  unregisterShortcuts() {
    const shortcutManager = this.hot.getShortcutManager();
    const gridContext = shortcutManager.getContext('grid');
    gridContext.removeShortcutsByGroup(SHORTCUTS_GROUP);
  }

  /**
   * Stash information about performed actions.
   *
   * @fires Hooks#beforeUndoStackChange
   * @fires Hooks#afterUndoStackChange
   * @fires Hooks#beforeRedoStackChange
   * @fires Hooks#afterRedoStackChange
   * @param {Function} wrappedAction The action descriptor wrapped in a closure.
   * @param {string} [source] Source of the action. It is defined just for more general actions (not related to plugins).
   */
  done(wrappedAction, source) {
    if (this.ignoreNewActions) {
      return;
    }
    const isBlockedByDefault = source === 'UndoRedo.undo' || source === 'UndoRedo.redo' || source === 'auto';
    if (isBlockedByDefault) {
      return;
    }
    const doneActionsCopy = this.doneActions.slice();
    const continueAction = this.hot.runHooks('beforeUndoStackChange', doneActionsCopy, source);
    if (continueAction === false) {
      return;
    }
    const newAction = wrappedAction();
    const undoneActionsCopy = this.undoneActions.slice();
    this.doneActions.push(newAction);
    this.hot.runHooks('afterUndoStackChange', doneActionsCopy, this.doneActions.slice());
    this.hot.runHooks('beforeRedoStackChange', undoneActionsCopy);
    this.undoneActions.length = 0;
    this.hot.runHooks('afterRedoStackChange', undoneActionsCopy, this.undoneActions.slice());
  }

  /**
   * Undo the last action performed to the table.
   *
   * @fires Hooks#beforeUndoStackChange
   * @fires Hooks#afterUndoStackChange
   * @fires Hooks#beforeRedoStackChange
   * @fires Hooks#afterRedoStackChange
   * @fires Hooks#beforeUndo
   * @fires Hooks#afterUndo
   */
  undo() {
    if (!this.isUndoAvailable()) {
      return;
    }
    const doneActionsCopy = this.doneActions.slice();
    this.hot.runHooks('beforeUndoStackChange', doneActionsCopy);
    const action = this.doneActions.pop();
    this.hot.runHooks('afterUndoStackChange', doneActionsCopy, this.doneActions.slice());
    const actionClone = (0, _object.deepClone)(action);
    const continueAction = this.hot.runHooks('beforeUndo', actionClone);
    if (continueAction === false) {
      return;
    }
    this.ignoreNewActions = true;
    const undoneActionsCopy = this.undoneActions.slice();
    this.hot.runHooks('beforeRedoStackChange', undoneActionsCopy);
    action.undo(this.hot, () => {
      this.ignoreNewActions = false;
      this.undoneActions.push(action);
    });
    this.hot.runHooks('afterRedoStackChange', undoneActionsCopy, this.undoneActions.slice());
    this.hot.runHooks('afterUndo', actionClone);
  }

  /**
   * Redo the previous action performed to the table (used to reverse an undo).
   *
   * @fires Hooks#beforeUndoStackChange
   * @fires Hooks#afterUndoStackChange
   * @fires Hooks#beforeRedoStackChange
   * @fires Hooks#afterRedoStackChange
   * @fires Hooks#beforeRedo
   * @fires Hooks#afterRedo
   */
  redo() {
    if (!this.isRedoAvailable()) {
      return;
    }
    const undoneActionsCopy = this.undoneActions.slice();
    this.hot.runHooks('beforeRedoStackChange', undoneActionsCopy);
    const action = this.undoneActions.pop();
    this.hot.runHooks('afterRedoStackChange', undoneActionsCopy, this.undoneActions.slice());
    const actionClone = (0, _object.deepClone)(action);
    const continueAction = this.hot.runHooks('beforeRedo', actionClone);
    if (continueAction === false) {
      return;
    }
    this.ignoreNewActions = true;
    const doneActionsCopy = this.doneActions.slice();
    this.hot.runHooks('beforeUndoStackChange', doneActionsCopy);
    action.redo(this.hot, () => {
      this.ignoreNewActions = false;
      this.doneActions.push(action);
    });
    this.hot.runHooks('afterUndoStackChange', doneActionsCopy, this.doneActions.slice());
    this.hot.runHooks('afterRedo', actionClone);
  }

  /**
   * Checks if undo action is available.
   *
   * @returns {boolean} Return `true` if undo can be performed, `false` otherwise.
   */
  isUndoAvailable() {
    return this.doneActions.length > 0;
  }

  /**
   * Checks if redo action is available.
   *
   * @returns {boolean} Return `true` if redo can be performed, `false` otherwise.
   */
  isRedoAvailable() {
    return this.undoneActions.length > 0;
  }

  /**
   * Clears undo and redo history.
   */
  clear() {
    this.doneActions.length = 0;
    this.undoneActions.length = 0;
  }
  /**
   * Destroys the plugin instance.
   */
  destroy() {
    this.clear();
    this.doneActions = null;
    this.undoneActions = null;
    super.destroy();
  }
}
exports.UndoRedo = UndoRedo;
function _onAfterChange(changes, source) {
  if (source === 'loadData') {
    this.clear();
  }
}
/**
 * Expose the plugin API to the Core. It is for backward compatibility and it should be removed in the future.
 */
function _exposeAPIToCore() {
  const deprecatedWarn = methodName => {
    if (!deprecationWarns.has(methodName)) {
      (0, _console.warn)((0, _templateLiteralTag.toSingleLine)`The "${methodName}" method is deprecated and it will be removed\x20
          from the Core API in the future. Please use the method from the UndoRedo plugin\x20
          (e.g. \`hotInstance.getPlugin("undoRedo").${methodName}()\`).`);
      deprecationWarns.add(methodName);
    }
  };

  /**
   * {@link UndoRedo#undo}.
   *
   * @alias undo
   * @memberof! Core#
   */
  this.hot.undo = () => {
    deprecatedWarn('undo');
    this.undo();
  };
  /**
   * {@link UndoRedo#redo}.
   *
   * @alias redo
   * @memberof! Core#
   */
  this.hot.redo = () => {
    deprecatedWarn('redo');
    this.redo();
  };
  /**
   * {@link UndoRedo#isUndoAvailable}.
   *
   * @alias isUndoAvailable
   * @memberof! Core#
   * @returns {boolean}
   */
  this.hot.isUndoAvailable = () => {
    deprecatedWarn('isUndoAvailable');
    return this.isUndoAvailable();
  };
  /**
   * {@link UndoRedo#isRedoAvailable}.
   *
   * @alias isRedoAvailable
   * @memberof! Core#
   * @returns {boolean}
   */
  this.hot.isRedoAvailable = () => {
    deprecatedWarn('isRedoAvailable');
    return this.isRedoAvailable();
  };
  /**
   * {@link UndoRedo#clear}.
   *
   * @alias clearUndo
   * @memberof! Core#
   */
  this.hot.clearUndo = () => {
    deprecatedWarn('clear');
    this.clear();
  };
  this.hot.undoRedo = this;
}
/**
 * Removes the plugin API from the Core. It is for backward compatibility and it should be removed in the future.
 */
function _removeAPIFromCore() {
  delete this.hot.undo;
  delete this.hot.redo;
  delete this.hot.isUndoAvailable;
  delete this.hot.isRedoAvailable;
  delete this.hot.clearUndo;
  delete this.hot.undoRedo;
}