import "core-js/modules/es.error.cause.js";
import "core-js/modules/esnext.iterator.constructor.js";
import "core-js/modules/esnext.iterator.reduce.js";
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
import { StretchStrategy } from "./_base.mjs";
/**
 * @typedef StretchStrategyCalcArgs
 * @property {number} viewportWidth The width of the viewport.
 */
/**
 * The strategy calculates only the last column widths to fill the viewport.
 *
 * @private
 * @class StretchLastStrategy
 */
var _lastColumnWidth = /*#__PURE__*/new WeakMap();
var _lastColumnIndex = /*#__PURE__*/new WeakMap();
export class StretchLastStrategy extends StretchStrategy {
  constructor() {
    super(...arguments);
    /**
     * The width of the last calculated column.
     *
     * @type {number}
     */
    _classPrivateFieldInitSpec(this, _lastColumnWidth, 0);
    /**
     * The index of the last calculated column.
     *
     * @type {number}
     */
    _classPrivateFieldInitSpec(this, _lastColumnIndex, -1);
  }
  /**
   * Prepares the strategy for the calculation.
   *
   * @param {StretchStrategyCalcArgs} calcArgs The calculation arguments.
   */
  prepare(calcArgs) {
    super.prepare(calcArgs);
    _classPrivateFieldSet(_lastColumnWidth, this, 0);
    _classPrivateFieldSet(_lastColumnIndex, this, -1);
  }

  /**
   * Sets the base widths of the columns with which the strategy will work with.
   *
   * @param {number} columnVisualIndex The visual index of the column.
   * @param {number} columnWidth The width of the column.
   */
  setColumnBaseWidth(columnVisualIndex, columnWidth) {
    super.setColumnBaseWidth(columnVisualIndex, columnWidth);
    _classPrivateFieldSet(_lastColumnIndex, this, columnVisualIndex);
    _classPrivateFieldSet(_lastColumnWidth, this, columnWidth);
  }

  /**
   * Calculates the columns widths.
   */
  calculate() {
    if (_classPrivateFieldGet(_lastColumnIndex, this) === -1) {
      return;
    }
    const allColumnsWidth = Array.from(this.baseWidths).reduce((sum, _ref) => {
      let [, width] = _ref;
      return sum + width;
    }, 0);
    const lastColumnWidth = Math.max(this.viewportWidth - allColumnsWidth + _classPrivateFieldGet(_lastColumnWidth, this), 0);
    this.stretchedWidths.set(_classPrivateFieldGet(_lastColumnIndex, this), lastColumnWidth);
  }
}