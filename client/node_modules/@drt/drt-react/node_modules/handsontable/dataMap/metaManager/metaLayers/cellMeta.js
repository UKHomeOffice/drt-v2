"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
require("core-js/modules/es.array.push.js");
var _object = require("../../../helpers/object");
var _utils = require("../utils");
var _lazyFactoryMap = _interopRequireDefault(require("../lazyFactoryMap"));
var _mixed = require("../../../helpers/mixed");
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
/* eslint-disable jsdoc/require-description-complete-sentence */
/**
 * @class CellMeta
 *
 * The cell meta object is a root of all settings defined for the specific cell rendered by the
 * Handsontable. Each cell meta inherits settings from higher layers. When a property doesn't
 * exist in that layer, it is looked up through a prototype to the highest layer. Starting
 * from CellMeta -> ColumnMeta and ending to GlobalMeta, which stores default settings. Adding,
 * removing, or changing property in that object has no direct reflection on any other layers.
 *
 * +-------------+
 * │ GlobalMeta  │
 * │ (prototype) │
 * +-------------+\
 *       │         \
 *       │          \
 *      \│/         _\|
 * +-------------+    +-------------+
 * │ TableMeta   │    │ ColumnMeta  │
 * │ (instance)  │    │ (prototype) │
 * +-------------+    +-------------+
 *                         │
 *                         │
 *                        \│/
 *                    +-------------+
 *                    │  CellMeta   │
 *                    │ (instance)  │
 *                    +-------------+
 */
/* eslint-enable jsdoc/require-description-complete-sentence */
class CellMeta {
  constructor(columnMeta) {
    /**
     * Reference to the ColumnMeta layer. While creating new cell meta objects, all new objects
     * inherit properties from the ColumnMeta layer.
     *
     * @type {ColumnMeta}
     */
    _defineProperty(this, "columnMeta", void 0);
    /**
     * Holder for cell meta objects, organized as a grid of LazyFactoryMap of LazyFactoryMaps.
     * The access to the cell meta object is done through access to the row defined by the physical
     * row index and then by accessing the second LazyFactory Map under the physical column index.
     *
     * @type {LazyFactoryMap<number, LazyFactoryMap<number, object>>}
     */
    _defineProperty(this, "metas", new _lazyFactoryMap.default(() => this._createRow()));
    this.columnMeta = columnMeta;
  }

  /**
   * Updates cell meta object by merging settings with the current state.
   *
   * @param {number} physicalRow The physical row index which points what cell meta object is updated.
   * @param {number} physicalColumn The physical column index which points what cell meta object is updated.
   * @param {object} settings An object to merge with.
   */
  updateMeta(physicalRow, physicalColumn, settings) {
    const meta = this.getMeta(physicalRow, physicalColumn);
    (0, _object.extend)(meta, settings);
    (0, _utils.extendByMetaType)(meta, settings);
  }

  /**
   * Creates one or more rows at specific position.
   *
   * @param {number} physicalRow The physical row index which points from what position the row is added.
   * @param {number} amount An amount of rows to add.
   */
  createRow(physicalRow, amount) {
    this.metas.insert(physicalRow, amount);
  }

  /**
   * Creates one or more columns at specific position.
   *
   * @param {number} physicalColumn The physical column index which points from what position the column is added.
   * @param {number} amount An amount of columns to add.
   */
  createColumn(physicalColumn, amount) {
    for (let i = 0; i < this.metas.size(); i++) {
      this.metas.obtain(i).insert(physicalColumn, amount);
    }
  }

  /**
   * Removes one or more rows from the collection.
   *
   * @param {number} physicalRow The physical row index which points from what position the row is removed.
   * @param {number} amount An amount of rows to remove.
   */
  removeRow(physicalRow, amount) {
    this.metas.remove(physicalRow, amount);
  }

  /**
   * Removes one or more columns from the collection.
   *
   * @param {number} physicalColumn The physical column index which points from what position the column is removed.
   * @param {number} amount An amount of columns to remove.
   */
  removeColumn(physicalColumn, amount) {
    for (let i = 0; i < this.metas.size(); i++) {
      this.metas.obtain(i).remove(physicalColumn, amount);
    }
  }

  /**
   * Gets settings object for this layer.
   *
   * @param {number} physicalRow The physical row index.
   * @param {number} physicalColumn The physical column index.
   * @param {string} [key] If the key exists its value will be returned, otherwise the whole cell meta object.
   * @returns {object}
   */
  getMeta(physicalRow, physicalColumn, key) {
    const cellMeta = this.metas.obtain(physicalRow).obtain(physicalColumn);
    if (key === undefined) {
      return cellMeta;
    }
    return cellMeta[key];
  }

  /**
   * Sets settings object for this layer defined by "key" property.
   *
   * @param {number} physicalRow The physical row index.
   * @param {number} physicalColumn The physical column index.
   * @param {string} key The property name to set.
   * @param {*} value Value to save.
   */
  setMeta(physicalRow, physicalColumn, key, value) {
    var _cellMeta$_automatica;
    const cellMeta = this.metas.obtain(physicalRow).obtain(physicalColumn);
    (_cellMeta$_automatica = cellMeta._automaticallyAssignedMetaProps) === null || _cellMeta$_automatica === void 0 || _cellMeta$_automatica.delete(key);
    cellMeta[key] = value;
  }

  /**
   * Removes a property defined by the "key" argument from the cell meta object.
   *
   * @param {number} physicalRow The physical row index.
   * @param {number} physicalColumn The physical column index.
   * @param {string} key The property name to remove.
   */
  removeMeta(physicalRow, physicalColumn, key) {
    const cellMeta = this.metas.obtain(physicalRow).obtain(physicalColumn);
    delete cellMeta[key];
  }

  /**
   * Returns all cell meta objects that were created during the Handsontable operation. As cell meta
   * objects are created lazy, the length of the returned collection depends on how and when the
   * table has asked for access to that meta objects.
   *
   * @returns {object[]}
   */
  getMetas() {
    const metas = [];
    const rows = Array.from(this.metas.values());
    for (let row = 0; row < rows.length; row++) {
      // Getting a meta for already added row (new row already exist - it has been added using `createRow` method).
      // However, is not ready until the first `getMeta` call (lazy loading).
      if ((0, _mixed.isDefined)(rows[row])) {
        metas.push(...rows[row].values());
      }
    }
    return metas;
  }

  /**
   * Returns all cell meta objects that were created during the Handsontable operation but for
   * specific row index.
   *
   * @param {number} physicalRow The physical row index.
   * @returns {object[]}
   */
  getMetasAtRow(physicalRow) {
    (0, _utils.assert)(() => (0, _utils.isUnsignedNumber)(physicalRow), 'Expecting an unsigned number.');
    const rowsMeta = new Map(this.metas);
    return rowsMeta.has(physicalRow) ? Array.from(rowsMeta.get(physicalRow).values()) : [];
  }

  /**
   * Clears all saved cell meta objects.
   */
  clearCache() {
    this.metas.clear();
  }

  /**
   * Creates and returns new structure for cell meta objects stored in columnar axis.
   *
   * @private
   * @returns {object}
   */
  _createRow() {
    return new _lazyFactoryMap.default(physicalColumn => this._createMeta(physicalColumn));
  }

  /**
   * Creates and returns new cell meta object with properties inherited from the column meta layer.
   *
   * @private
   * @param {number} physicalColumn The physical column index.
   * @returns {object}
   */
  _createMeta(physicalColumn) {
    const ColumnMeta = this.columnMeta.getMetaConstructor(physicalColumn);
    return new ColumnMeta();
  }
}
exports.default = CellMeta;