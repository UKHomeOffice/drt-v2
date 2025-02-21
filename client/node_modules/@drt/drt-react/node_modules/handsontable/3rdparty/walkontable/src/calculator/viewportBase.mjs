import "core-js/modules/es.error.cause.js";
import "core-js/modules/esnext.iterator.constructor.js";
import "core-js/modules/esnext.iterator.for-each.js";
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
/**
 * @typedef {object} ColumnsCalculationType
 * @property {number | null} startColumn The column index of the first column in the viewport.
 * @property {number | null} endColumn The column index of the last column in the viewport.
 * @property {number} count Total number of columns.
 * @property {number | null} startPosition Position of the first fully column (in px).
 * @property {boolean} isVisibleInTrimmingContainer Determines if the viewport is visible in the trimming container.
 */
/**
 * @typedef {object} RowsCalculationType
 * @property {number | null} startRow The row index of the first row in the viewport.
 * @property {number | null} endRow The row index of the last row in the viewport.
 * @property {number} count Total number of rows.
 * @property {number | null} startPosition Position of the first fully row (in px).
 * @property {boolean} isVisibleInTrimmingContainer Determines if the viewport is visible in the trimming container.
 */
/**
 * @class ViewportBaseCalculator
 */
export class ViewportBaseCalculator {
  constructor(calculationTypes) {
    /**
     * The calculation types to be performed.
     *
     * @type {Array}
     */
    _defineProperty(this, "calculationTypes", []);
    /**
     * The calculation results.
     *
     * @type {Map<string, ColumnsCalculationType | RowsCalculationType>}
     */
    _defineProperty(this, "calculationResults", new Map());
    this.calculationTypes = calculationTypes;
  }

  /**
   * Initializes all calculators (triggers all calculators before calculating the rows/columns sizes).
   *
   * @param {*} context The context object (rows or columns viewport calculator).
   */
  _initialize(context) {
    this.calculationTypes.forEach(_ref => {
      let [id, calculator] = _ref;
      this.calculationResults.set(id, calculator);
      calculator.initialize(context);
    });
  }

  /**
   * Processes the row/column at the given index.
   *
   * @param {number} index The index of the row/column.
   * @param {*} context The context object (rows or columns viewport calculator).
   */
  _process(index, context) {
    this.calculationTypes.forEach(_ref2 => {
      let [, calculator] = _ref2;
      return calculator.process(index, context);
    });
  }

  /**
   * Finalizes all calculators (triggers all calculators after calculating the rows/columns sizes).
   *
   * @param {*} context The context object (rows or columns viewport calculator).
   */
  _finalize(context) {
    this.calculationTypes.forEach(_ref3 => {
      let [, calculator] = _ref3;
      return calculator.finalize(context);
    });
  }

  /**
   * Gets the results for the given calculator.
   *
   * @param {string} calculatorId The id of the calculator.
   * @returns {ColumnsCalculationType | RowsCalculationType}
   */
  getResultsFor(calculatorId) {
    return this.calculationResults.get(calculatorId);
  }
}