import "core-js/modules/es.error.cause.js";
import "core-js/modules/es.array.push.js";
import "core-js/modules/es.json.stringify.js";
import "core-js/modules/es.set.difference.v2.js";
import "core-js/modules/es.set.intersection.v2.js";
import "core-js/modules/es.set.is-disjoint-from.v2.js";
import "core-js/modules/es.set.is-subset-of.v2.js";
import "core-js/modules/es.set.is-superset-of.v2.js";
import "core-js/modules/es.set.symmetric-difference.v2.js";
import "core-js/modules/es.set.union.v2.js";
import "core-js/modules/esnext.iterator.constructor.js";
import "core-js/modules/esnext.iterator.every.js";
import "core-js/modules/esnext.iterator.find.js";
import "core-js/modules/esnext.iterator.for-each.js";
import "core-js/modules/esnext.iterator.map.js";
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
import { BasePlugin } from "../base/index.mjs";
import staticRegister from "../../utils/staticRegister.mjs";
import { error, warn } from "../../helpers/console.mjs";
import { isNumeric } from "../../helpers/number.mjs";
import { isDefined, isUndefined } from "../../helpers/mixed.mjs";
import { setupEngine, setupSheet, unregisterEngine, getRegisteredHotInstances } from "./engine/register.mjs";
import { isEscapedFormulaExpression, unescapeFormulaExpression, isDate, isDateValid, getDateInHfFormat, getDateFromExcelDate, getDateInHotFormat, isFormula } from "./utils.mjs";
import { getEngineSettingsWithOverrides, haveEngineSettingsChanged } from "./engine/settings.mjs";
import { isArrayOfArrays } from "../../helpers/data.mjs";
import { toUpperCaseFirst } from "../../helpers/string.mjs";
import { Hooks } from "../../core/hooks/index.mjs";
import IndexSyncer from "./indexSyncer/index.mjs";
export const PLUGIN_KEY = 'formulas';
export const SETTING_KEYS = ['maxRows', 'maxColumns', 'language'];
export const PLUGIN_PRIORITY = 260;
Hooks.getSingleton().register('afterNamedExpressionAdded');
Hooks.getSingleton().register('afterNamedExpressionRemoved');
Hooks.getSingleton().register('afterSheetAdded');
Hooks.getSingleton().register('afterSheetRemoved');
Hooks.getSingleton().register('afterSheetRenamed');
Hooks.getSingleton().register('afterFormulasValuesUpdate');

// This function will be used for detecting changes coming from the `UndoRedo` plugin. This kind of change won't be
// handled by whole body of listeners and therefore won't change undo/redo stack inside engine provided by HyperFormula.
// HyperFormula's `undo` and `redo` methods will do it instead. Please keep in mind that undo/redo stacks inside
// instances of Handsontable and HyperFormula should be synced (number of actions should be the same).
const isBlockedSource = source => source === 'UndoRedo.undo' || source === 'UndoRedo.redo' || source === 'auto';

/**
 * This plugin allows you to perform Excel-like calculations in your business applications. It does it by an
 * integration with our other product, [HyperFormula](https://github.com/handsontable/hyperformula/), which is a
 * powerful calculation engine with an extensive number of features.
 *
 * To test out HyperFormula, see [this guide](@/guides/formulas/formula-calculation/formula-calculation.md#available-functions).
 *
 * @plugin Formulas
 * @class Formulas
 */
var _internalOperationPending = /*#__PURE__*/new WeakMap();
var _hotWasInitializedWithEmptyData = /*#__PURE__*/new WeakMap();
var _engineListeners = /*#__PURE__*/new WeakMap();
var _Formulas_brand = /*#__PURE__*/new WeakSet();
export class Formulas extends BasePlugin {
  constructor() {
    var _this;
    super(...arguments);
    _this = this;
    /**
     * The hook allows to translate the formula value to calculated value before it goes to the
     * validator function.
     *
     * @param {*} value The cell value to validate.
     * @param {number} visualRow The visual row index.
     * @param {number|string} prop The visual column index or property name of the column.
     * @returns {*} Returns value to validate.
     */
    _classPrivateMethodInitSpec(this, _Formulas_brand);
    /**
     * Flag used to bypass hooks in internal operations.
     *
     * @private
     * @type {boolean}
     */
    _classPrivateFieldInitSpec(this, _internalOperationPending, false);
    /**
     * Flag needed to mark if Handsontable was initialized with no data.
     * (Required to work around the fact, that Handsontable auto-generates sample data, when no data is provided).
     *
     * @type {boolean}
     */
    _classPrivateFieldInitSpec(this, _hotWasInitializedWithEmptyData, false);
    /**
     * The list of the HyperFormula listeners.
     *
     * @type {Array}
     */
    _classPrivateFieldInitSpec(this, _engineListeners, [['valuesUpdated', function () {
      for (var _len = arguments.length, args = new Array(_len), _key = 0; _key < _len; _key++) {
        args[_key] = arguments[_key];
      }
      return _assertClassBrand(_Formulas_brand, _this, _onEngineValuesUpdated).call(_this, ...args);
    }], ['namedExpressionAdded', function () {
      for (var _len2 = arguments.length, args = new Array(_len2), _key2 = 0; _key2 < _len2; _key2++) {
        args[_key2] = arguments[_key2];
      }
      return _assertClassBrand(_Formulas_brand, _this, _onEngineNamedExpressionsAdded).call(_this, ...args);
    }], ['namedExpressionRemoved', function () {
      for (var _len3 = arguments.length, args = new Array(_len3), _key3 = 0; _key3 < _len3; _key3++) {
        args[_key3] = arguments[_key3];
      }
      return _assertClassBrand(_Formulas_brand, _this, _onEngineNamedExpressionsRemoved).call(_this, ...args);
    }], ['sheetAdded', function () {
      for (var _len4 = arguments.length, args = new Array(_len4), _key4 = 0; _key4 < _len4; _key4++) {
        args[_key4] = arguments[_key4];
      }
      return _assertClassBrand(_Formulas_brand, _this, _onEngineSheetAdded).call(_this, ...args);
    }], ['sheetRenamed', function () {
      for (var _len5 = arguments.length, args = new Array(_len5), _key5 = 0; _key5 < _len5; _key5++) {
        args[_key5] = arguments[_key5];
      }
      return _assertClassBrand(_Formulas_brand, _this, _onEngineSheetRenamed).call(_this, ...args);
    }], ['sheetRemoved', function () {
      for (var _len6 = arguments.length, args = new Array(_len6), _key6 = 0; _key6 < _len6; _key6++) {
        args[_key6] = arguments[_key6];
      }
      return _assertClassBrand(_Formulas_brand, _this, _onEngineSheetRemoved).call(_this, ...args);
    }]]);
    /**
     * Static register used to set up one global HyperFormula instance.
     * TODO: currently used in tests, might be removed later.
     *
     * @private
     * @type {object}
     */
    _defineProperty(this, "staticRegister", staticRegister('formulas'));
    /**
     * The engine instance that will be used for this instance of Handsontable.
     *
     * @type {HyperFormula|null}
     */
    _defineProperty(this, "engine", null);
    /**
     * HyperFormula's sheet name.
     *
     * @type {string|null}
     */
    _defineProperty(this, "sheetName", null);
    /**
     * Index synchronizer responsible for manipulating with some general options related to indexes synchronization.
     *
     * @type {IndexSyncer|null}
     */
    _defineProperty(this, "indexSyncer", null);
    /**
     * Index synchronizer responsible for syncing the order of HOT and HF's data for the axis of the rows.
     *
     * @type {AxisSyncer|null}
     */
    _defineProperty(this, "rowAxisSyncer", null);
    /**
     * Index synchronizer responsible for syncing the order of HOT and HF's data for the axis of the columns.
     *
     * @type {AxisSyncer|null}
     */
    _defineProperty(this, "columnAxisSyncer", null);
  }
  static get PLUGIN_KEY() {
    return PLUGIN_KEY;
  }
  static get PLUGIN_PRIORITY() {
    return PLUGIN_PRIORITY;
  }
  static get SETTING_KEYS() {
    return [PLUGIN_KEY, ...SETTING_KEYS];
  }
  /**
   * HyperFormula's sheet id.
   *
   * @type {number|null}
   */
  get sheetId() {
    return this.sheetName === null ? null : this.engine.getSheetId(this.sheetName);
  }

  /**
   * Checks if the plugin is enabled in the handsontable settings. This method is executed in {@link Hooks#beforeInit}
   * hook and if it returns `true` then the {@link Formulas#enablePlugin} method is called.
   *
   * @returns {boolean}
   */
  isEnabled() {
    /* eslint-disable no-unneeded-ternary */
    return this.hot.getSettings()[PLUGIN_KEY] ? true : false;
  }

  /**
   * Enables the plugin functionality for this Handsontable instance.
   */
  enablePlugin() {
    var _setupEngine,
      _this2 = this;
    if (this.enabled) {
      return;
    }
    this.engine = (_setupEngine = setupEngine(this.hot)) !== null && _setupEngine !== void 0 ? _setupEngine : this.engine;
    if (!this.engine) {
      warn('Missing the required `engine` key in the Formulas settings. Please fill it with either an' + ' engine class or an engine instance.');
      return;
    }

    // Useful for disabling -> enabling the plugin using `updateSettings` or the API.
    if (this.sheetName !== null && !this.engine.doesSheetExist(this.sheetName)) {
      const newSheetName = this.addSheet(this.sheetName, this.hot.getSourceDataArray());
      if (newSheetName !== false) {
        this.sheetName = newSheetName;
      }
    }
    this.addHook('beforeLoadData', function () {
      for (var _len7 = arguments.length, args = new Array(_len7), _key7 = 0; _key7 < _len7; _key7++) {
        args[_key7] = arguments[_key7];
      }
      return _assertClassBrand(_Formulas_brand, _this2, _onBeforeLoadData).call(_this2, ...args);
    });
    this.addHook('afterLoadData', function () {
      for (var _len8 = arguments.length, args = new Array(_len8), _key8 = 0; _key8 < _len8; _key8++) {
        args[_key8] = arguments[_key8];
      }
      return _assertClassBrand(_Formulas_brand, _this2, _onAfterLoadData).call(_this2, ...args);
    });

    // The `updateData` hooks utilize the same logic as the `loadData` hooks.
    this.addHook('beforeUpdateData', function () {
      for (var _len9 = arguments.length, args = new Array(_len9), _key9 = 0; _key9 < _len9; _key9++) {
        args[_key9] = arguments[_key9];
      }
      return _assertClassBrand(_Formulas_brand, _this2, _onBeforeLoadData).call(_this2, ...args);
    });
    this.addHook('afterUpdateData', function () {
      for (var _len10 = arguments.length, args = new Array(_len10), _key10 = 0; _key10 < _len10; _key10++) {
        args[_key10] = arguments[_key10];
      }
      return _assertClassBrand(_Formulas_brand, _this2, _onAfterLoadData).call(_this2, ...args);
    });
    this.addHook('modifyData', function () {
      for (var _len11 = arguments.length, args = new Array(_len11), _key11 = 0; _key11 < _len11; _key11++) {
        args[_key11] = arguments[_key11];
      }
      return _assertClassBrand(_Formulas_brand, _this2, _onModifyData).call(_this2, ...args);
    });
    this.addHook('modifySourceData', function () {
      for (var _len12 = arguments.length, args = new Array(_len12), _key12 = 0; _key12 < _len12; _key12++) {
        args[_key12] = arguments[_key12];
      }
      return _assertClassBrand(_Formulas_brand, _this2, _onModifySourceData).call(_this2, ...args);
    });
    this.addHook('beforeValidate', function () {
      for (var _len13 = arguments.length, args = new Array(_len13), _key13 = 0; _key13 < _len13; _key13++) {
        args[_key13] = arguments[_key13];
      }
      return _assertClassBrand(_Formulas_brand, _this2, _onBeforeValidate).call(_this2, ...args);
    });
    this.addHook('afterSetSourceDataAtCell', function () {
      for (var _len14 = arguments.length, args = new Array(_len14), _key14 = 0; _key14 < _len14; _key14++) {
        args[_key14] = arguments[_key14];
      }
      return _assertClassBrand(_Formulas_brand, _this2, _onAfterSetSourceDataAtCell).call(_this2, ...args);
    });
    this.addHook('afterSetDataAtCell', function () {
      for (var _len15 = arguments.length, args = new Array(_len15), _key15 = 0; _key15 < _len15; _key15++) {
        args[_key15] = arguments[_key15];
      }
      return _assertClassBrand(_Formulas_brand, _this2, _onAfterSetDataAtCell).call(_this2, ...args);
    });
    this.addHook('afterSetDataAtRowProp', function () {
      for (var _len16 = arguments.length, args = new Array(_len16), _key16 = 0; _key16 < _len16; _key16++) {
        args[_key16] = arguments[_key16];
      }
      return _assertClassBrand(_Formulas_brand, _this2, _onAfterSetDataAtCell).call(_this2, ...args);
    });
    this.addHook('beforeCreateRow', function () {
      for (var _len17 = arguments.length, args = new Array(_len17), _key17 = 0; _key17 < _len17; _key17++) {
        args[_key17] = arguments[_key17];
      }
      return _assertClassBrand(_Formulas_brand, _this2, _onBeforeCreateRow).call(_this2, ...args);
    });
    this.addHook('beforeCreateCol', function () {
      for (var _len18 = arguments.length, args = new Array(_len18), _key18 = 0; _key18 < _len18; _key18++) {
        args[_key18] = arguments[_key18];
      }
      return _assertClassBrand(_Formulas_brand, _this2, _onBeforeCreateCol).call(_this2, ...args);
    });
    this.addHook('afterCreateRow', function () {
      for (var _len19 = arguments.length, args = new Array(_len19), _key19 = 0; _key19 < _len19; _key19++) {
        args[_key19] = arguments[_key19];
      }
      return _assertClassBrand(_Formulas_brand, _this2, _onAfterCreateRow).call(_this2, ...args);
    });
    this.addHook('afterCreateCol', function () {
      for (var _len20 = arguments.length, args = new Array(_len20), _key20 = 0; _key20 < _len20; _key20++) {
        args[_key20] = arguments[_key20];
      }
      return _assertClassBrand(_Formulas_brand, _this2, _onAfterCreateCol).call(_this2, ...args);
    });
    this.addHook('beforeRemoveRow', function () {
      for (var _len21 = arguments.length, args = new Array(_len21), _key21 = 0; _key21 < _len21; _key21++) {
        args[_key21] = arguments[_key21];
      }
      return _assertClassBrand(_Formulas_brand, _this2, _onBeforeRemoveRow).call(_this2, ...args);
    });
    this.addHook('beforeRemoveCol', function () {
      for (var _len22 = arguments.length, args = new Array(_len22), _key22 = 0; _key22 < _len22; _key22++) {
        args[_key22] = arguments[_key22];
      }
      return _assertClassBrand(_Formulas_brand, _this2, _onBeforeRemoveCol).call(_this2, ...args);
    });
    this.addHook('afterRemoveRow', function () {
      for (var _len23 = arguments.length, args = new Array(_len23), _key23 = 0; _key23 < _len23; _key23++) {
        args[_key23] = arguments[_key23];
      }
      return _assertClassBrand(_Formulas_brand, _this2, _onAfterRemoveRow).call(_this2, ...args);
    });
    this.addHook('afterRemoveCol', function () {
      for (var _len24 = arguments.length, args = new Array(_len24), _key24 = 0; _key24 < _len24; _key24++) {
        args[_key24] = arguments[_key24];
      }
      return _assertClassBrand(_Formulas_brand, _this2, _onAfterRemoveCol).call(_this2, ...args);
    });
    this.indexSyncer = new IndexSyncer(this.hot.rowIndexMapper, this.hot.columnIndexMapper, postponedAction => {
      this.hot.addHookOnce('init', () => {
        // Engine is initialized after executing callback to `afterLoadData` hook. Thus, some actions on indexes should
        // be postponed.
        postponedAction();
      });
    });
    this.rowAxisSyncer = this.indexSyncer.getForAxis('row');
    this.columnAxisSyncer = this.indexSyncer.getForAxis('column');
    this.hot.addHook('afterRowSequenceChange', this.rowAxisSyncer.getIndexesChangeSyncMethod());
    this.hot.addHook('afterColumnSequenceChange', this.columnAxisSyncer.getIndexesChangeSyncMethod());
    this.hot.addHook('beforeRowMove', (movedRows, finalIndex, _, movePossible) => {
      this.rowAxisSyncer.storeMovesInformation(movedRows, finalIndex, movePossible);
    });
    this.hot.addHook('beforeColumnMove', (movedColumns, finalIndex, _, movePossible) => {
      this.columnAxisSyncer.storeMovesInformation(movedColumns, finalIndex, movePossible);
    });
    this.hot.addHook('afterRowMove', (movedRows, finalIndex, dropIndex, movePossible, orderChanged) => {
      this.rowAxisSyncer.calculateAndSyncMoves(movePossible, orderChanged);
    });
    this.hot.addHook('afterColumnMove', (movedColumns, finalIndex, dropIndex, movePossible, orderChanged) => {
      this.columnAxisSyncer.calculateAndSyncMoves(movePossible, orderChanged);
    });
    this.hot.addHook('beforeColumnFreeze', (column, freezePerformed) => {
      this.columnAxisSyncer.storeMovesInformation([column], this.hot.getSettings().fixedColumnsStart, freezePerformed);
    });
    this.hot.addHook('afterColumnFreeze', (_, freezePerformed) => {
      this.columnAxisSyncer.calculateAndSyncMoves(freezePerformed, freezePerformed);
    });
    this.hot.addHook('beforeColumnUnfreeze', (column, unfreezePerformed) => {
      this.columnAxisSyncer.storeMovesInformation([column], this.hot.getSettings().fixedColumnsStart - 1, unfreezePerformed);
    });
    this.hot.addHook('afterColumnUnfreeze', (_, unfreezePerformed) => {
      this.columnAxisSyncer.calculateAndSyncMoves(unfreezePerformed, unfreezePerformed);
    });

    // TODO: Actions related to overwriting dates from HOT format to HF default format are done as callback to this
    // hook, because some hooks, such as `afterLoadData` doesn't have information about composed cell properties.
    // Another hooks are triggered to late for setting HF's engine data needed for some actions.
    this.addHook('afterCellMetaReset', function () {
      for (var _len25 = arguments.length, args = new Array(_len25), _key25 = 0; _key25 < _len25; _key25++) {
        args[_key25] = arguments[_key25];
      }
      return _assertClassBrand(_Formulas_brand, _this2, _onAfterCellMetaReset).call(_this2, ...args);
    });

    // Handling undo actions on data just using HyperFormula's UndoRedo mechanism
    this.addHook('beforeUndo', () => {
      this.indexSyncer.setPerformUndo(true);
      this.engine.undo();
    });

    // Handling redo actions on data just using HyperFormula's UndoRedo mechanism
    this.addHook('beforeRedo', () => {
      this.indexSyncer.setPerformRedo(true);
      this.engine.redo();
    });
    this.addHook('afterUndo', () => {
      this.indexSyncer.setPerformUndo(false);
    });
    this.addHook('afterUndo', () => {
      this.indexSyncer.setPerformRedo(false);
    });
    this.addHook('afterDetachChild', function () {
      for (var _len26 = arguments.length, args = new Array(_len26), _key26 = 0; _key26 < _len26; _key26++) {
        args[_key26] = arguments[_key26];
      }
      return _assertClassBrand(_Formulas_brand, _this2, _onAfterDetachChild).call(_this2, ...args);
    });
    this.addHook('beforeAutofill', function () {
      for (var _len27 = arguments.length, args = new Array(_len27), _key27 = 0; _key27 < _len27; _key27++) {
        args[_key27] = arguments[_key27];
      }
      return _assertClassBrand(_Formulas_brand, _this2, _onBeforeAutofill).call(_this2, ...args);
    });
    _classPrivateFieldGet(_engineListeners, this).forEach(_ref => {
      let [eventName, listener] = _ref;
      return this.engine.on(eventName, listener);
    });
    super.enablePlugin();
  }

  /**
   * Disables the plugin functionality for this Handsontable instance.
   */
  disablePlugin() {
    _classPrivateFieldGet(_engineListeners, this).forEach(_ref2 => {
      let [eventName, listener] = _ref2;
      return this.engine.off(eventName, listener);
    });
    unregisterEngine(this.engine, this.hot);
    this.engine = null;
    super.disablePlugin();
  }

  /**
   * Triggered on `updateSettings`.
   *
   * @private
   * @param {object} newSettings New set of settings passed to the `updateSettings` method.
   */
  updatePlugin(newSettings) {
    const newEngineSettings = getEngineSettingsWithOverrides(this.hot.getSettings());
    if (haveEngineSettingsChanged(this.engine.getConfig(), newEngineSettings)) {
      this.engine.updateConfig(newEngineSettings);
    }
    const pluginSettings = this.hot.getSettings()[PLUGIN_KEY];
    if (isDefined(pluginSettings) && isDefined(pluginSettings.sheetName) && pluginSettings.sheetName !== this.sheetName) {
      this.switchSheet(pluginSettings.sheetName);
    }

    // If no data was passed to the `updateSettings` method and no sheet is connected to the instance -> create a
    // new sheet using the currently used data. Otherwise, it will be handled by the `afterLoadData` call.
    if (!newSettings.data && this.sheetName === null) {
      const sheetName = this.hot.getSettings()[PLUGIN_KEY].sheetName;
      if (sheetName && this.engine.doesSheetExist(sheetName)) {
        this.switchSheet(this.sheetName);
      } else {
        this.sheetName = this.addSheet(sheetName !== null && sheetName !== void 0 ? sheetName : undefined, this.hot.getSourceDataArray());
      }
    }
    super.updatePlugin(newSettings);
  }

  /**
   * Destroys the plugin instance.
   */
  destroy() {
    _classPrivateFieldGet(_engineListeners, this).forEach(_ref3 => {
      var _this$engine;
      let [eventName, listener] = _ref3;
      return (_this$engine = this.engine) === null || _this$engine === void 0 ? void 0 : _this$engine.off(eventName, listener);
    });
    _classPrivateFieldSet(_engineListeners, this, null);
    unregisterEngine(this.engine, this.hot);
    this.engine = null;
    super.destroy();
  }

  /**
   * Add a sheet to the shared HyperFormula instance.
   *
   * @param {string|null} [sheetName] The new sheet name. If not provided (or a null is passed), will be
   * auto-generated by HyperFormula.
   * @param {Array} [sheetData] Data passed to the shared HyperFormula instance. Has to be declared as an array of
   * arrays - array of objects is not supported in this scenario.
   * @returns {boolean|string} `false` if the data format is unusable or it is impossible to add a new sheet to the
   * engine, the created sheet name otherwise.
   */
  addSheet(sheetName, sheetData) {
    if (isDefined(sheetData) && !isArrayOfArrays(sheetData)) {
      warn('The provided data should be an array of arrays.');
      return false;
    }
    if (sheetName !== undefined && sheetName !== null && this.engine.doesSheetExist(sheetName)) {
      warn('Sheet with the provided name already exists.');
      return false;
    }
    try {
      const actualSheetName = this.engine.addSheet(sheetName !== null && sheetName !== void 0 ? sheetName : undefined);
      if (sheetData) {
        this.engine.setSheetContent(this.engine.getSheetId(actualSheetName), sheetData);
      }
      return actualSheetName;
    } catch (e) {
      warn(e.message);
      return false;
    }
  }

  /**
   * Switch the sheet used as data in the Handsontable instance (it loads the data from the shared HyperFormula
   * instance).
   *
   * @param {string} sheetName Sheet name used in the shared HyperFormula instance.
   */
  switchSheet(sheetName) {
    if (!this.engine.doesSheetExist(sheetName)) {
      error(`The sheet named \`${sheetName}\` does not exist, switch aborted.`);
      return;
    }
    this.sheetName = sheetName;
    const serialized = this.engine.getSheetSerialized(this.sheetId);
    if (serialized.length > 0) {
      this.hot.loadData(serialized, `${toUpperCaseFirst(PLUGIN_KEY)}.switchSheet`);
    }
  }

  /**
   * Get the cell type under specified visual coordinates.
   *
   * @param {number} row Visual row index.
   * @param {number} column Visual column index.
   * @param {number} [sheet] The target sheet id, defaults to the current sheet.
   * @returns {string} Possible values: 'FORMULA' | 'VALUE' | 'ARRAYFORMULA' | 'EMPTY'.
   */
  getCellType(row, column) {
    let sheet = arguments.length > 2 && arguments[2] !== undefined ? arguments[2] : this.sheetId;
    const physicalRow = this.hot.toPhysicalRow(row);
    const physicalColumn = this.hot.toPhysicalColumn(column);
    if (physicalRow !== null && physicalColumn !== null) {
      return this.engine.getCellType({
        sheet,
        row: this.rowAxisSyncer.getHfIndexFromVisualIndex(row),
        col: this.columnAxisSyncer.getHfIndexFromVisualIndex(column)
      });
    } else {
      // Should return `EMPTY` when out of bounds (according to the test cases).
      return 'EMPTY';
    }
  }

  /**
   * Returns `true` if under specified visual coordinates is formula.
   *
   * @param {number} row Visual row index.
   * @param {number} column Visual column index.
   * @param {number} [sheet] The target sheet id, defaults to the current sheet.
   * @returns {boolean}
   */
  isFormulaCellType(row, column) {
    let sheet = arguments.length > 2 && arguments[2] !== undefined ? arguments[2] : this.sheetId;
    return this.engine.doesCellHaveFormula({
      sheet,
      row: this.rowAxisSyncer.getHfIndexFromVisualIndex(row),
      col: this.columnAxisSyncer.getHfIndexFromVisualIndex(column)
    });
  }

  /**
   * Renders dependent sheets (handsontable instances) based on the changes - list of the
   * recalculated dependent cells.
   *
   * @private
   * @param {object[]} dependentCells The values and location of applied changes within HF engine.
   * @param {boolean} [renderSelf] `true` if it's supposed to render itself, `false` otherwise.
   */
  renderDependentSheets(dependentCells) {
    let renderSelf = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : false;
    const affectedSheetIds = new Set();
    dependentCells.forEach(change => {
      var _change$address;
      // For the Named expression the address is empty, hence the `sheetId` is undefined.
      const sheetId = change === null || change === void 0 || (_change$address = change.address) === null || _change$address === void 0 ? void 0 : _change$address.sheet;
      if (sheetId !== undefined) {
        if (!affectedSheetIds.has(sheetId)) {
          affectedSheetIds.add(sheetId);
        }
      }
    });
    getRegisteredHotInstances(this.engine).forEach((relatedHot, sheetId) => {
      if ((renderSelf || sheetId !== this.sheetId) && affectedSheetIds.has(sheetId)) {
        var _relatedHot$view;
        relatedHot.render();
        (_relatedHot$view = relatedHot.view) === null || _relatedHot$view === void 0 || _relatedHot$view.adjustElementsSize();
      }
    });
  }

  /**
   * Validates dependent cells based on the cells that are modified by the change.
   *
   * @private
   * @param {object[]} dependentCells The values and location of applied changes within HF engine.
   * @param {object[]} [changedCells] The values and location of applied changes by developer (through API or UI).
   */
  validateDependentCells(dependentCells) {
    let changedCells = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : [];
    const stringifyAddress = change => {
      var _change$address2;
      const {
        row,
        col,
        sheet
      } = (_change$address2 = change === null || change === void 0 ? void 0 : change.address) !== null && _change$address2 !== void 0 ? _change$address2 : {};
      return isDefined(sheet) ? `${sheet}:${row}x${col}` : '';
    };
    const changedCellsSet = new Set(changedCells.map(change => stringifyAddress(change)));
    dependentCells.forEach(change => {
      var _change$address3, _change$address4;
      const {
        row,
        col
      } = (_change$address3 = change.address) !== null && _change$address3 !== void 0 ? _change$address3 : {};

      // Don't try to validate cells outside of the visual part of the table.
      if (isDefined(row) === false || isDefined(col) === false || row >= this.hot.countRows() || col >= this.hot.countCols()) {
        return;
      }

      // For the Named expression the address is empty, hence the `sheetId` is undefined.
      const sheetId = change === null || change === void 0 || (_change$address4 = change.address) === null || _change$address4 === void 0 ? void 0 : _change$address4.sheet;
      const addressId = stringifyAddress(change);

      // Validate the cells that depend on the calculated formulas. Skip that cells
      // where the user directly changes the values - the Core triggers those validators.
      if (sheetId !== undefined && !changedCellsSet.has(addressId)) {
        const boundHot = getRegisteredHotInstances(this.engine).get(sheetId);

        // if `sheetId` is not bound to any Handsontable instance, skip the validation process
        if (!boundHot) {
          return;
        }

        // It will just re-render certain cell when necessary.
        boundHot.validateCell(boundHot.getDataAtCell(row, col), boundHot.getCellMeta(row, col), () => {});
      }
    });
  }

  /**
   * Sync a change from the change-related hooks with the engine.
   *
   * @private
   * @param {number} row Visual row index.
   * @param {number} column Visual column index.
   * @param {Handsontable.CellValue} newValue New value.
   * @returns {Array} Array of changes exported from the engine.
   */
  syncChangeWithEngine(row, column, newValue) {
    const address = {
      row: this.rowAxisSyncer.getHfIndexFromVisualIndex(row),
      col: this.columnAxisSyncer.getHfIndexFromVisualIndex(column),
      sheet: this.sheetId
    };
    if (!this.engine.isItPossibleToSetCellContents(address)) {
      warn(`Not possible to set cell data at ${JSON.stringify(address)}`);
      return;
    }
    const cellMeta = this.hot.getCellMeta(row, column);
    if (isDate(newValue, cellMeta.type)) {
      if (isDateValid(newValue, cellMeta.dateFormat)) {
        // Rewriting date in HOT format to HF format.
        newValue = getDateInHfFormat(newValue, cellMeta.dateFormat);
      } else if (isFormula(newValue) === false) {
        // Escaping value from date parsing using "'" sign (HF feature).
        newValue = `'${newValue}`;
      }
    }
    return this.engine.setCellContents(address, newValue);
  }
}
function _onBeforeValidate(value, visualRow, prop) {
  const visualColumn = this.hot.propToCol(prop);
  if (this.isFormulaCellType(visualRow, visualColumn)) {
    const address = {
      row: this.rowAxisSyncer.getHfIndexFromVisualIndex(visualRow),
      col: this.columnAxisSyncer.getHfIndexFromVisualIndex(visualColumn),
      sheet: this.sheetId
    };
    const cellMeta = this.hot.getCellMeta(visualRow, visualColumn);
    let cellValue = this.engine.getCellValue(address); // Date as an integer (Excel-like date).

    if (cellMeta.type === 'date' && isNumeric(cellValue)) {
      cellValue = getDateFromExcelDate(cellValue, cellMeta.dateFormat);
    }

    // If `cellValue` is an object it is expected to be an error
    return typeof cellValue === 'object' && cellValue !== null ? cellValue.value : cellValue;
  }
  return value;
}
/**
 * `onBeforeAutofill` hook callback.
 *
 * @param {Array[]} fillData The data that was used to fill the `targetRange`. If `beforeAutofill` was used
 * and returned `[[]]`, this will be the same object that was returned from `beforeAutofill`.
 * @param {CellRange} sourceRange The range values will be filled from.
 * @param {CellRange} targetRange The range new values will be filled into.
 * @returns {boolean|*}
 */
function _onBeforeAutofill(fillData, sourceRange, targetRange) {
  const {
    row: sourceTopStartRow,
    col: sourceTopStartColumn
  } = sourceRange.getTopStartCorner();
  const {
    row: sourceBottomEndRow,
    col: sourceBottomEndColumn
  } = sourceRange.getBottomEndCorner();
  const {
    row: targetTopStartRow,
    col: targetTopStartColumn
  } = targetRange.getTopStartCorner();
  const {
    row: targetBottomEndRow,
    col: targetBottomEndColumn
  } = targetRange.getBottomEndCorner();
  const engineSourceRange = {
    start: {
      row: this.rowAxisSyncer.getHfIndexFromVisualIndex(sourceTopStartRow),
      col: this.columnAxisSyncer.getHfIndexFromVisualIndex(sourceTopStartColumn),
      sheet: this.sheetId
    },
    end: {
      row: this.rowAxisSyncer.getHfIndexFromVisualIndex(sourceBottomEndRow),
      col: this.columnAxisSyncer.getHfIndexFromVisualIndex(sourceBottomEndColumn),
      sheet: this.sheetId
    }
  };
  const engineTargetRange = {
    start: {
      row: this.rowAxisSyncer.getHfIndexFromVisualIndex(targetTopStartRow),
      col: this.columnAxisSyncer.getHfIndexFromVisualIndex(targetTopStartColumn),
      sheet: this.sheetId
    },
    end: {
      row: this.rowAxisSyncer.getHfIndexFromVisualIndex(targetBottomEndRow),
      col: this.columnAxisSyncer.getHfIndexFromVisualIndex(targetBottomEndColumn),
      sheet: this.sheetId
    }
  };

  // Blocks the autofill operation if HyperFormula says that at least one of
  // the underlying cell's contents cannot be set.
  if (this.engine.isItPossibleToSetCellContents(engineTargetRange) === false) {
    return false;
  }
  const fillRangeData = this.engine.getFillRangeData(engineSourceRange, engineTargetRange);
  const {
    row: sourceStartRow,
    col: sourceStartColumn
  } = engineSourceRange.start;
  const {
    row: sourceEndRow,
    col: sourceEndColumn
  } = engineSourceRange.end;
  const populationRowLength = sourceEndRow - sourceStartRow + 1;
  const populationColumnLength = sourceEndColumn - sourceStartColumn + 1;
  for (let populatedRowIndex = 0; populatedRowIndex < fillRangeData.length; populatedRowIndex += 1) {
    for (let populatedColumnIndex = 0; populatedColumnIndex < fillRangeData[populatedRowIndex].length; populatedColumnIndex += 1) {
      const populatedValue = fillRangeData[populatedRowIndex][populatedColumnIndex];
      const sourceRow = sourceStartRow + populatedRowIndex % populationRowLength;
      const sourceColumn = sourceStartColumn + populatedColumnIndex % populationColumnLength;
      const sourceCellMeta = this.hot.getCellMeta(sourceRow, sourceColumn);
      if (isDate(populatedValue, sourceCellMeta.type)) {
        if (populatedValue.startsWith('\'')) {
          // Populating values on HOT side without apostrophe.
          fillRangeData[populatedRowIndex][populatedColumnIndex] = populatedValue.slice(1);
        } else if (this.isFormulaCellType(sourceRow, sourceColumn, this.sheetId) === false) {
          // Populating date in proper format, coming from the source cell.
          fillRangeData[populatedRowIndex][populatedColumnIndex] = getDateInHotFormat(populatedValue, sourceCellMeta.dateFormat);
        }
      }
    }
  }
  return fillRangeData;
}
/**
 * `beforeLoadData` hook callback.
 *
 * @param {Array} sourceData Array of arrays or array of objects containing data.
 * @param {boolean} initialLoad Flag that determines whether the data has been loaded during the initialization.
 * @param {string} [source] Source of the call.
 */
function _onBeforeLoadData(sourceData, initialLoad) {
  let source = arguments.length > 2 && arguments[2] !== undefined ? arguments[2] : '';
  if (source.includes(toUpperCaseFirst(PLUGIN_KEY))) {
    return;
  }

  // This flag needs to be defined, because not passing data to HOT results in HOT auto-generating a `null`-filled
  // initial dataset.
  _classPrivateFieldSet(_hotWasInitializedWithEmptyData, this, isUndefined(this.hot.getSettings().data));
}
/**
 * Callback to `afterCellMetaReset` hook which is triggered after setting cell meta.
 */
function _onAfterCellMetaReset() {
  const sourceDataArray = this.hot.getSourceDataArray();
  let valueChanged = false;
  sourceDataArray.forEach((rowData, rowIndex) => {
    rowData.forEach((cellValue, columnIndex) => {
      const cellMeta = this.hot.getCellMeta(rowIndex, columnIndex);
      const dateFormat = cellMeta.dateFormat;
      if (isDate(cellValue, cellMeta.type)) {
        valueChanged = true;
        if (isDateValid(cellValue, dateFormat)) {
          // Rewriting date in HOT format to HF format.
          sourceDataArray[rowIndex][columnIndex] = getDateInHfFormat(cellValue, dateFormat);
        } else if (this.isFormulaCellType(rowIndex, columnIndex) === false) {
          // Escaping value from date parsing using "'" sign (HF feature).
          sourceDataArray[rowIndex][columnIndex] = `'${cellValue}`;
        }
      }
    });
  });
  if (valueChanged === true) {
    _classPrivateFieldSet(_internalOperationPending, this, true);
    this.engine.setSheetContent(this.sheetId, sourceDataArray);
    _classPrivateFieldSet(_internalOperationPending, this, false);
  }
}
/**
 * `afterLoadData` hook callback.
 *
 * @param {Array} sourceData Array of arrays or array of objects containing data.
 * @param {boolean} initialLoad Flag that determines whether the data has been loaded during the initialization.
 * @param {string} [source] Source of the call.
 */
function _onAfterLoadData(sourceData, initialLoad) {
  let source = arguments.length > 2 && arguments[2] !== undefined ? arguments[2] : '';
  if (source.includes(toUpperCaseFirst(PLUGIN_KEY))) {
    return;
  }
  this.sheetName = setupSheet(this.engine, this.hot.getSettings()[PLUGIN_KEY].sheetName);
  if (!_classPrivateFieldGet(_hotWasInitializedWithEmptyData, this)) {
    const sourceDataArray = this.hot.getSourceDataArray();
    if (this.engine.isItPossibleToReplaceSheetContent(this.sheetId, sourceDataArray)) {
      _classPrivateFieldSet(_internalOperationPending, this, true);
      const dependentCells = this.engine.setSheetContent(this.sheetId, sourceDataArray);
      this.indexSyncer.setupSyncEndpoint(this.engine, this.sheetId);
      this.renderDependentSheets(dependentCells);
      _classPrivateFieldSet(_internalOperationPending, this, false);
    }
  } else {
    this.switchSheet(this.sheetName);
  }
}
/**
 * `modifyData` hook callback.
 *
 * @param {number} physicalRow Physical row index.
 * @param {number} visualColumn Visual column index.
 * @param {object} valueHolder Object which contains original value which can be modified by overwriting `.value`
 *   property.
 * @param {string} ioMode String which indicates for what operation hook is fired (`get` or `set`).
 */
function _onModifyData(physicalRow, visualColumn, valueHolder, ioMode) {
  if (ioMode !== 'get' || _classPrivateFieldGet(_internalOperationPending, this) || this.sheetName === null || !this.engine.doesSheetExist(this.sheetName)) {
    return;
  }
  const visualRow = this.hot.toVisualRow(physicalRow);
  if (visualRow === null || visualColumn === null) {
    return;
  }

  // `column` is here as visual index because of inconsistencies related to hook execution in `src/dataMap`.
  const isFormulaCellType = this.isFormulaCellType(visualRow, visualColumn);
  if (!isFormulaCellType) {
    const cellType = this.getCellType(visualRow, visualColumn);
    if (cellType !== 'ARRAY') {
      if (isEscapedFormulaExpression(valueHolder.value)) {
        valueHolder.value = unescapeFormulaExpression(valueHolder.value);
      }
      return;
    }
  }
  const address = {
    row: this.rowAxisSyncer.getHfIndexFromVisualIndex(visualRow),
    col: this.columnAxisSyncer.getHfIndexFromVisualIndex(visualColumn),
    sheet: this.sheetId
  };
  let cellValue = this.engine.getCellValue(address); // Date as an integer (Excel like date).

  // TODO: Workaround. We use HOT's `getCellsMeta` method instead of HOT's `getCellMeta` method. Getting cell meta
  // using the second method lead to execution of the `cells` method. Using the `getDataAtCell` (which may be useful)
  // in a callback to the `cells` method leads to triggering the `modifyData` hook. Thus, the `onModifyData` callback
  // is executed once again and it cause creation of an infinite loop.
  let cellMeta = this.hot.getCellsMeta().find(singleCellMeta => singleCellMeta.visualRow === visualRow && singleCellMeta.visualCol === visualColumn);
  if (cellMeta === undefined) {
    cellMeta = {};
  }
  if (cellMeta.type === 'date' && isNumeric(cellValue)) {
    cellValue = getDateFromExcelDate(cellValue, cellMeta.dateFormat);
  }

  // If `cellValue` is an object it is expected to be an error
  const value = typeof cellValue === 'object' && cellValue !== null ? cellValue.value : cellValue;
  valueHolder.value = value;
}
/**
 * `modifySourceData` hook callback.
 *
 * @param {number} row Physical row index.
 * @param {number|string} columnOrProp Physical column index or prop.
 * @param {object} valueHolder Object which contains original value which can be modified by overwriting `.value`
 *   property.
 * @param {string} ioMode String which indicates for what operation hook is fired (`get` or `set`).
 */
function _onModifySourceData(row, columnOrProp, valueHolder, ioMode) {
  if (ioMode !== 'get' || _classPrivateFieldGet(_internalOperationPending, this) || this.sheetName === null || !this.engine.doesSheetExist(this.sheetName)) {
    return;
  }
  const visualRow = this.hot.toVisualRow(row);
  const visualColumn = this.hot.propToCol(columnOrProp);
  if (visualRow === null || visualColumn === null) {
    return;
  }

  // `column` is here as visual index because of inconsistencies related to hook execution in `src/dataMap`.
  const isFormulaCellType = this.isFormulaCellType(visualRow, visualColumn);
  if (!isFormulaCellType) {
    const cellType = this.getCellType(visualRow, visualColumn);
    if (cellType !== 'ARRAY') {
      return;
    }
  }
  const dimensions = this.engine.getSheetDimensions(this.engine.getSheetId(this.sheetName));

  // Don't actually change the source data if HyperFormula is not
  // initialized yet. This is done to allow the `afterLoadData` hook to
  // load the existing source data with `Handsontable#getSourceDataArray`
  // properly.
  if (dimensions.width === 0 && dimensions.height === 0) {
    return;
  }
  const address = {
    row: this.rowAxisSyncer.getHfIndexFromVisualIndex(visualRow),
    col: this.columnAxisSyncer.getHfIndexFromVisualIndex(visualColumn),
    sheet: this.sheetId
  };
  valueHolder.value = this.engine.getCellSerialized(address);
}
/**
 * `onAfterSetDataAtCell` hook callback.
 *
 * @param {Array[]} changes An array of changes in format [[row, prop, oldValue, value], ...].
 * @param {string} [source] String that identifies source of hook call
 *                          ([list of all available sources]{@link https://handsontable.com/docs/javascript-data-grid/events-and-hooks/#handsontable-hooks}).
 */
function _onAfterSetDataAtCell(changes, source) {
  if (isBlockedSource(source)) {
    return;
  }
  const outOfBoundsChanges = [];
  const changedCells = [];
  const dependentCells = this.engine.batch(() => {
    changes.forEach(_ref4 => {
      let [visualRow, prop,, newValue] = _ref4;
      const visualColumn = this.hot.propToCol(prop);
      const physicalRow = this.hot.toPhysicalRow(visualRow);
      const physicalColumn = this.hot.toPhysicalColumn(visualColumn);
      const address = {
        row: this.rowAxisSyncer.getHfIndexFromVisualIndex(visualRow),
        col: this.columnAxisSyncer.getHfIndexFromVisualIndex(visualColumn),
        sheet: this.sheetId
      };
      if (physicalRow !== null && physicalColumn !== null) {
        this.syncChangeWithEngine(visualRow, visualColumn, newValue);
      } else {
        outOfBoundsChanges.push([visualRow, visualColumn, newValue]);
      }
      changedCells.push({
        address
      });
    });
  });
  if (outOfBoundsChanges.length) {
    // Workaround for rows/columns being created two times (by HOT and the engine).
    // (unfortunately, this requires an extra re-render)
    this.hot.addHookOnce('afterChange', () => {
      const outOfBoundsDependentCells = this.engine.batch(() => {
        outOfBoundsChanges.forEach(_ref5 => {
          let [row, column, newValue] = _ref5;
          this.syncChangeWithEngine(row, column, newValue);
        });
      });
      this.renderDependentSheets(outOfBoundsDependentCells, true);
    });
  }
  this.renderDependentSheets(dependentCells);
  this.validateDependentCells(dependentCells, changedCells);
}
/**
 * `onAfterSetSourceDataAtCell` hook callback.
 *
 * @param {Array[]} changes An array of changes in format [[row, column, oldValue, value], ...].
 * @param {string} [source] String that identifies source of hook call
 *                          ([list of all available sources]{@link https://handsontable.com/docs/javascript-data-grid/events-and-hooks/#handsontable-hooks}).
 */
function _onAfterSetSourceDataAtCell(changes, source) {
  if (isBlockedSource(source)) {
    return;
  }
  const dependentCells = [];
  const changedCells = [];
  changes.forEach(_ref6 => {
    let [visualRow, prop,, newValue] = _ref6;
    const visualColumn = this.hot.propToCol(prop);
    if (!isNumeric(visualColumn)) {
      return;
    }
    const address = {
      row: this.rowAxisSyncer.getHfIndexFromVisualIndex(visualRow),
      col: this.columnAxisSyncer.getHfIndexFromVisualIndex(visualColumn),
      sheet: this.sheetId
    };
    if (!this.engine.isItPossibleToSetCellContents(address)) {
      warn(`Not possible to set source cell data at ${JSON.stringify(address)}`);
      return;
    }
    changedCells.push({
      address
    });
    dependentCells.push(...this.engine.setCellContents(address, newValue));
  });
  this.renderDependentSheets(dependentCells);
  this.validateDependentCells(dependentCells, changedCells);
}
/**
 * `beforeCreateRow` hook callback.
 *
 * @param {number} visualRow Represents the visual index of first newly created row in the data source array.
 * @param {number} amount Number of newly created rows in the data source array.
 * @returns {*|boolean} If false is returned the action is canceled.
 */
function _onBeforeCreateRow(visualRow, amount) {
  let hfRowIndex = this.rowAxisSyncer.getHfIndexFromVisualIndex(visualRow);
  if (visualRow >= this.hot.countRows()) {
    hfRowIndex = visualRow; // Row beyond the table boundaries.
  }
  if (this.sheetId === null || !this.engine.doesSheetExist(this.sheetName) || !this.engine.isItPossibleToAddRows(this.sheetId, [hfRowIndex, amount])) {
    return false;
  }
}
/**
 * `beforeCreateCol` hook callback.
 *
 * @param {number} visualColumn Represents the visual index of first newly created column in the data source.
 * @param {number} amount Number of newly created columns in the data source.
 * @returns {*|boolean} If false is returned the action is canceled.
 */
function _onBeforeCreateCol(visualColumn, amount) {
  let hfColumnIndex = this.columnAxisSyncer.getHfIndexFromVisualIndex(visualColumn);
  if (visualColumn >= this.hot.countCols()) {
    hfColumnIndex = visualColumn; // Column beyond the table boundaries.
  }
  if (this.sheetId === null || !this.engine.doesSheetExist(this.sheetName) || !this.engine.isItPossibleToAddColumns(this.sheetId, [hfColumnIndex, amount])) {
    return false;
  }
}
/**
 * `beforeRemoveRow` hook callback.
 *
 * @param {number} row Visual index of starter row.
 * @param {number} amount Amount of rows to be removed.
 * @param {number[]} physicalRows An array of physical rows removed from the data source.
 * @returns {*|boolean} If false is returned the action is canceled.
 */
function _onBeforeRemoveRow(row, amount, physicalRows) {
  const hfRows = this.rowAxisSyncer.setRemovedHfIndexes(physicalRows);
  const possible = hfRows.every(hfRow => {
    return this.engine.isItPossibleToRemoveRows(this.sheetId, [hfRow, 1]);
  });
  return possible === false ? false : undefined;
}
/**
 * `beforeRemoveCol` hook callback.
 *
 * @param {number} col Visual index of starter column.
 * @param {number} amount Amount of columns to be removed.
 * @param {number[]} physicalColumns An array of physical columns removed from the data source.
 * @returns {*|boolean} If false is returned the action is canceled.
 */
function _onBeforeRemoveCol(col, amount, physicalColumns) {
  const hfColumns = this.columnAxisSyncer.setRemovedHfIndexes(physicalColumns);
  const possible = hfColumns.every(hfColumn => {
    return this.engine.isItPossibleToRemoveColumns(this.sheetId, [hfColumn, 1]);
  });
  return possible === false ? false : undefined;
}
/**
 * `afterCreateRow` hook callback.
 *
 * @param {number} visualRow Represents the visual index of first newly created row in the data source array.
 * @param {number} amount Number of newly created rows in the data source array.
 * @param {string} [source] String that identifies source of hook call
 *                          ([list of all available sources]{@link https://handsontable.com/docs/javascript-data-grid/events-and-hooks/#handsontable-hooks}).
 */
function _onAfterCreateRow(visualRow, amount, source) {
  if (isBlockedSource(source)) {
    return;
  }
  const changes = this.engine.addRows(this.sheetId, [this.rowAxisSyncer.getHfIndexFromVisualIndex(visualRow), amount]);
  this.renderDependentSheets(changes);
}
/**
 * `afterCreateCol` hook callback.
 *
 * @param {number} visualColumn Represents the visual index of first newly created column in the data source.
 * @param {number} amount Number of newly created columns in the data source.
 * @param {string} [source] String that identifies source of hook call
 *                          ([list of all available sources]{@link https://handsontable.com/docs/javascript-data-grid/events-and-hooks/#handsontable-hooks}).
 */
function _onAfterCreateCol(visualColumn, amount, source) {
  if (isBlockedSource(source)) {
    return;
  }
  const changes = this.engine.addColumns(this.sheetId, [this.columnAxisSyncer.getHfIndexFromVisualIndex(visualColumn), amount]);
  this.renderDependentSheets(changes);
}
/**
 * `afterRemoveRow` hook callback.
 *
 * @param {number} row Visual index of starter row.
 * @param {number} amount An amount of removed rows.
 * @param {number[]} physicalRows An array of physical rows removed from the data source.
 * @param {string} [source] String that identifies source of hook call
 *                          ([list of all available sources]{@link https://handsontable.com/docs/javascript-data-grid/events-and-hooks/#handsontable-hooks}).
 */
function _onAfterRemoveRow(row, amount, physicalRows, source) {
  if (isBlockedSource(source)) {
    return;
  }
  const descendingHfRows = this.rowAxisSyncer.getRemovedHfIndexes().sort((a, b) => b - a); // sort numeric values descending

  const changes = this.engine.batch(() => {
    descendingHfRows.forEach(hfRow => {
      this.engine.removeRows(this.sheetId, [hfRow, 1]);
    });
  });
  this.renderDependentSheets(changes);
}
/**
 * `afterRemoveCol` hook callback.
 *
 * @param {number} col Visual index of starter column.
 * @param {number} amount An amount of removed columns.
 * @param {number[]} physicalColumns An array of physical columns removed from the data source.
 * @param {string} [source] String that identifies source of hook call
 *                          ([list of all available sources]{@link https://handsontable.com/docs/javascript-data-grid/events-and-hooks/#handsontable-hooks}).
 */
function _onAfterRemoveCol(col, amount, physicalColumns, source) {
  if (isBlockedSource(source)) {
    return;
  }
  const descendingHfColumns = this.columnAxisSyncer.getRemovedHfIndexes().sort((a, b) => b - a); // sort numeric values descending

  const changes = this.engine.batch(() => {
    descendingHfColumns.forEach(hfColumn => {
      this.engine.removeColumns(this.sheetId, [hfColumn, 1]);
    });
  });
  this.renderDependentSheets(changes);
}
/**
 * `afterDetachChild` hook callback.
 * Used to sync the data of the rows detached in the Nested Rows plugin with the engine's dataset.
 *
 * @param {object} parent An object representing the parent from which the element was detached.
 * @param {object} element The detached element.
 * @param {number} finalElementRowIndex The final row index of the detached element.
 */
function _onAfterDetachChild(parent, element, finalElementRowIndex) {
  var _element$__children;
  _classPrivateFieldSet(_internalOperationPending, this, true);
  const rowsData = this.hot.getSourceDataArray(finalElementRowIndex, 0, finalElementRowIndex + (((_element$__children = element.__children) === null || _element$__children === void 0 ? void 0 : _element$__children.length) || 0), this.hot.countSourceCols());
  _classPrivateFieldSet(_internalOperationPending, this, false);
  rowsData.forEach((row, relativeRowIndex) => {
    row.forEach((value, colIndex) => {
      this.engine.setCellContents({
        col: colIndex,
        row: finalElementRowIndex + relativeRowIndex,
        sheet: this.sheetId
      }, [[value]]);
    });
  });
}
/**
 * Called when a value is updated in the engine.
 *
 * @fires Hooks#afterFormulasValuesUpdate
 * @param {Array} changes The values and location of applied changes.
 */
function _onEngineValuesUpdated(changes) {
  this.hot.runHooks('afterFormulasValuesUpdate', changes);
}
/**
 * Called when a named expression is added to the engine instance.
 *
 * @fires Hooks#afterNamedExpressionAdded
 * @param {string} namedExpressionName The name of the added expression.
 * @param {Array} changes The values and location of applied changes.
 */
function _onEngineNamedExpressionsAdded(namedExpressionName, changes) {
  this.hot.runHooks('afterNamedExpressionAdded', namedExpressionName, changes);
}
/**
 * Called when a named expression is removed from the engine instance.
 *
 * @fires Hooks#afterNamedExpressionRemoved
 * @param {string} namedExpressionName The name of the removed expression.
 * @param {Array} changes The values and location of applied changes.
 */
function _onEngineNamedExpressionsRemoved(namedExpressionName, changes) {
  this.hot.runHooks('afterNamedExpressionRemoved', namedExpressionName, changes);
}
/**
 * Called when a new sheet is added to the engine instance.
 *
 * @fires Hooks#afterSheetAdded
 * @param {string} addedSheetDisplayName The name of the added sheet.
 */
function _onEngineSheetAdded(addedSheetDisplayName) {
  this.hot.runHooks('afterSheetAdded', addedSheetDisplayName);
}
/**
 * Called when a sheet in the engine instance is renamed.
 *
 * @fires Hooks#afterSheetRenamed
 * @param {string} oldDisplayName The old name of the sheet.
 * @param {string} newDisplayName The new name of the sheet.
 */
function _onEngineSheetRenamed(oldDisplayName, newDisplayName) {
  this.sheetName = newDisplayName;
  this.hot.runHooks('afterSheetRenamed', oldDisplayName, newDisplayName);
}
/**
 * Called when a sheet is removed from the engine instance.
 *
 * @fires Hooks#afterSheetRemoved
 * @param {string} removedSheetDisplayName The removed sheet name.
 * @param {Array} changes The values and location of applied changes.
 */
function _onEngineSheetRemoved(removedSheetDisplayName, changes) {
  this.hot.runHooks('afterSheetRemoved', removedSheetDisplayName, changes);
}