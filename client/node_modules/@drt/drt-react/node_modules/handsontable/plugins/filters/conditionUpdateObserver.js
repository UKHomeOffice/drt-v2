"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
require("core-js/modules/es.array.push.js");
require("core-js/modules/esnext.iterator.constructor.js");
require("core-js/modules/esnext.iterator.filter.js");
var _array = require("../../helpers/array");
var _object = require("../../helpers/object");
var _function = require("../../helpers/function");
var _localHooks = _interopRequireDefault(require("../../mixins/localHooks"));
var _conditionCollection = _interopRequireDefault(require("./conditionCollection"));
var _dataFilter = _interopRequireDefault(require("./dataFilter"));
var _utils = require("./utils");
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
/**
 * Class which is designed for observing changes in condition collection. When condition is changed by user at specified
 * column it's necessary to update all conditions defined after this edited one.
 *
 * Object fires `update` hook for every column conditions change.
 *
 * @private
 * @class ConditionUpdateObserver
 */
var _ConditionUpdateObserver_brand = /*#__PURE__*/new WeakSet();
class ConditionUpdateObserver {
  constructor(hot, conditionCollection) {
    let columnDataFactory = arguments.length > 2 && arguments[2] !== undefined ? arguments[2] : () => [];
    /**
     * On before modify condition (add or remove from collection),.
     *
     * @param {number} column Column index.
     * @private
     */
    _classPrivateMethodInitSpec(this, _ConditionUpdateObserver_brand);
    /**
     * Handsontable instance.
     *
     * @type {Core}
     */
    _defineProperty(this, "hot", void 0);
    /**
     * Reference to the instance of {@link ConditionCollection}.
     *
     * @type {ConditionCollection}
     */
    _defineProperty(this, "conditionCollection", void 0);
    /**
     * Function which provide source data factory for specified column.
     *
     * @type {Function}
     */
    _defineProperty(this, "columnDataFactory", void 0);
    /**
     * Collected changes when grouping is enabled.
     *
     * @type {Array}
     * @default []
     */
    _defineProperty(this, "changes", []);
    /**
     * Flag which determines if grouping events is enabled.
     *
     * @type {boolean}
     */
    _defineProperty(this, "grouping", false);
    /**
     * The latest known position of edited conditions at specified column index.
     *
     * @type {number}
     * @default -1
     */
    _defineProperty(this, "latestEditedColumnPosition", -1);
    /**
     * The latest known order of conditions stack.
     *
     * @type {Array}
     */
    _defineProperty(this, "latestOrderStack", []);
    this.hot = hot;
    this.conditionCollection = conditionCollection;
    this.columnDataFactory = columnDataFactory;
    this.conditionCollection.addLocalHook('beforeRemove', column => _assertClassBrand(_ConditionUpdateObserver_brand, this, _onConditionBeforeModify).call(this, column));
    this.conditionCollection.addLocalHook('afterRemove', column => this.updateStatesAtColumn(column));
    this.conditionCollection.addLocalHook('afterAdd', column => this.updateStatesAtColumn(column));
    this.conditionCollection.addLocalHook('beforeClean', () => _assertClassBrand(_ConditionUpdateObserver_brand, this, _onConditionBeforeClean).call(this));
    this.conditionCollection.addLocalHook('afterClean', () => _assertClassBrand(_ConditionUpdateObserver_brand, this, _onConditionAfterClean).call(this));
  }

  /**
   * Enable grouping changes. Grouping is helpful in situations when a lot of conditions is added in one moment. Instead of
   * trigger `update` hook for every condition by adding/removing you can group this changes and call `flush` method to trigger
   * it once.
   */
  groupChanges() {
    this.grouping = true;
  }

  /**
   * Flush all collected changes. This trigger `update` hook for every previously collected change from condition collection.
   */
  flush() {
    this.grouping = false;
    (0, _array.arrayEach)(this.changes, column => {
      this.updateStatesAtColumn(column);
    });
    this.changes.length = 0;
  }
  /**
   * Update all related states which should be changed after invoking changes applied to current column.
   *
   * @param {number} column The column index.
   * @param {object} conditionArgsChange Object describing condition changes which can be handled by filters on `update` hook.
   * It contains keys `conditionKey` and `conditionValue` which refers to change specified key of condition to specified value
   * based on referred keys.
   */
  updateStatesAtColumn(column, conditionArgsChange) {
    var _this = this;
    if (this.grouping) {
      if (this.changes.indexOf(column) === -1) {
        this.changes.push(column);
      }
      return;
    }
    const allConditions = this.conditionCollection.exportAllConditions();
    let editedColumnPosition = this.conditionCollection.getColumnStackPosition(column);
    if (editedColumnPosition === -1) {
      editedColumnPosition = this.latestEditedColumnPosition;
    }

    // Collection of all conditions defined before currently edited `column` (without edited one)
    const conditionsBefore = allConditions.slice(0, editedColumnPosition);
    // Collection of all conditions defined after currently edited `column` (with edited one)
    const conditionsAfter = allConditions.slice(editedColumnPosition);

    // Make sure that conditionAfter doesn't contain edited column conditions
    if (conditionsAfter.length && conditionsAfter[0].column === column) {
      conditionsAfter.shift();
    }
    const visibleDataFactory = (0, _function.curry)(function (curriedConditionsBefore, curriedColumn) {
      let conditionsStack = arguments.length > 2 && arguments[2] !== undefined ? arguments[2] : [];
      const splitConditionCollection = new _conditionCollection.default(_this.hot, false);
      const curriedConditionsBeforeArray = [].concat(curriedConditionsBefore, conditionsStack);

      // Create new condition collection to determine what rows should be visible in "filter by value" box
      // in the next conditions in the chain
      splitConditionCollection.importAllConditions(curriedConditionsBeforeArray);
      const allRows = _this.columnDataFactory(curriedColumn);
      let visibleRows;
      if (splitConditionCollection.isEmpty()) {
        visibleRows = allRows;
      } else {
        visibleRows = new _dataFilter.default(splitConditionCollection, columnData => _this.columnDataFactory(columnData)).filter();
      }
      visibleRows = (0, _array.arrayMap)(visibleRows, rowData => rowData.meta.visualRow);
      const visibleRowsAssertion = (0, _utils.createArrayAssertion)(visibleRows);
      splitConditionCollection.destroy();
      return (0, _array.arrayFilter)(allRows, rowData => visibleRowsAssertion(rowData.meta.visualRow));
    })(conditionsBefore);
    const editedConditions = [].concat(this.conditionCollection.getConditions(column));
    this.runLocalHooks('update', {
      editedConditionStack: {
        column,
        conditions: editedConditions
      },
      dependentConditionStacks: conditionsAfter,
      filteredRowsFactory: visibleDataFactory,
      conditionArgsChange
    });
  }

  /**
   * On before conditions clean listener.
   *
   * @private
   */

  /**
   * Destroy instance.
   */
  destroy() {
    this.clearLocalHooks();
    (0, _object.objectEach)(this, (value, property) => {
      this[property] = null;
    });
  }
}
function _onConditionBeforeModify(column) {
  this.latestEditedColumnPosition = this.conditionCollection.getColumnStackPosition(column);
}
function _onConditionBeforeClean() {
  this.latestOrderStack = this.conditionCollection.getFilteredColumns();
}
/**
 * On after conditions clean listener.
 *
 * @private
 */
function _onConditionAfterClean() {
  (0, _array.arrayEach)(this.latestOrderStack, column => {
    this.updateStatesAtColumn(column);
  });
}
(0, _object.mixin)(ConditionUpdateObserver, _localHooks.default);
var _default = exports.default = ConditionUpdateObserver;