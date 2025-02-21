"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
var _axisSyncer = _interopRequireDefault(require("./axisSyncer"));
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
/**
 * @private
 * @class IndexSyncer
 * @description
 *
 * Indexes synchronizer responsible for providing logic for syncing actions done on indexes for HOT to actions performed
 * on HF's.
 *
 */
var _rowIndexSyncer = /*#__PURE__*/new WeakMap();
var _columnIndexSyncer = /*#__PURE__*/new WeakMap();
var _postponeAction = /*#__PURE__*/new WeakMap();
var _isPerformingUndo = /*#__PURE__*/new WeakMap();
var _isPerformingRedo = /*#__PURE__*/new WeakMap();
var _engine = /*#__PURE__*/new WeakMap();
var _sheetId = /*#__PURE__*/new WeakMap();
class IndexSyncer {
  constructor(rowIndexMapper, columnIndexMapper, postponeAction) {
    /**
     * Indexes synchronizer for the axis of the rows.
     *
     * @private
     * @type {AxisSyncer}
     */
    _classPrivateFieldInitSpec(this, _rowIndexSyncer, void 0);
    /**
     * Indexes synchronizer for the axis of the columns.
     *
     * @private
     * @type {AxisSyncer}
     */
    _classPrivateFieldInitSpec(this, _columnIndexSyncer, void 0);
    /**
     * Method which will postpone execution of some action (needed when synchronization endpoint isn't setup yet).
     *
     * @private
     * @type {Function}
     */
    _classPrivateFieldInitSpec(this, _postponeAction, void 0);
    /**
     * Flag informing whether undo is already performed (we don't perform synchronization in such case).
     *
     * @private
     * @type {boolean}
     */
    _classPrivateFieldInitSpec(this, _isPerformingUndo, false);
    /**
     * Flag informing whether redo is already performed (we don't perform synchronization in such case).
     *
     * @private
     * @type {boolean}
     */
    _classPrivateFieldInitSpec(this, _isPerformingRedo, false);
    /**
     * The HF's engine instance which will be synced.
     *
     * @private
     * @type {HyperFormula|null}
     */
    _classPrivateFieldInitSpec(this, _engine, null);
    /**
     * HyperFormula's sheet name.
     *
     * @private
     * @type {string|null}
     */
    _classPrivateFieldInitSpec(this, _sheetId, null);
    _classPrivateFieldSet(_rowIndexSyncer, this, new _axisSyncer.default('row', rowIndexMapper, this));
    _classPrivateFieldSet(_columnIndexSyncer, this, new _axisSyncer.default('column', columnIndexMapper, this));
    _classPrivateFieldSet(_postponeAction, this, postponeAction);
  }

  /**
   * Gets index synchronizer for a particular axis.
   *
   * @param {'row'|'column'} indexType Type of indexes.
   * @returns {AxisSyncer}
   */
  getForAxis(indexType) {
    if (indexType === 'row') {
      return _classPrivateFieldGet(_rowIndexSyncer, this);
    }
    return _classPrivateFieldGet(_columnIndexSyncer, this);
  }

  /**
   * Sets flag informing whether an undo action is already performed (we don't execute synchronization in such case).
   *
   * @param {boolean} flagValue Boolean value for the flag.
   */
  setPerformUndo(flagValue) {
    _classPrivateFieldSet(_isPerformingUndo, this, flagValue);
  }

  /**
   * Sets flag informing whether a redo action is already performed (we don't execute synchronization in such case).
   *
   * @param {boolean} flagValue Boolean value for the flag.
   */
  setPerformRedo(flagValue) {
    _classPrivateFieldSet(_isPerformingRedo, this, flagValue);
  }

  /**
   * Gets information whether redo or undo action is already performed (we don't execute synchronization in such case).
   *
   * @private
   * @returns {boolean}
   */
  isPerformingUndoRedo() {
    return _classPrivateFieldGet(_isPerformingUndo, this) || _classPrivateFieldGet(_isPerformingRedo, this);
  }

  /**
   * Gets HyperFormula's sheet id.
   *
   * @returns {string|null}
   */
  getSheetId() {
    return _classPrivateFieldGet(_sheetId, this);
  }

  /**
   * Gets engine instance that will be used for handled instance of Handsontable.
   *
   * @type {HyperFormula|null}
   */
  getEngine() {
    return _classPrivateFieldGet(_engine, this);
  }

  /**
   * Gets method which will postpone execution of some action (needed when synchronization endpoint isn't setup yet).
   *
   * @returns {Function}
   */
  getPostponeAction() {
    return _classPrivateFieldGet(_postponeAction, this);
  }

  /**
   * Setups a synchronization endpoint.
   *
   * @param {HyperFormula|null} engine The HF's engine instance which will be synced.
   * @param {string|null} sheetId HyperFormula's sheet name.
   */
  setupSyncEndpoint(engine, sheetId) {
    _classPrivateFieldSet(_engine, this, engine);
    _classPrivateFieldSet(_sheetId, this, sheetId);
    _classPrivateFieldGet(_rowIndexSyncer, this).init();
    _classPrivateFieldGet(_columnIndexSyncer, this).init();
  }
}
var _default = exports.default = IndexSyncer;