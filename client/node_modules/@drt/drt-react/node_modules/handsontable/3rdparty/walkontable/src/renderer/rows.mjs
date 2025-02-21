import "core-js/modules/es.error.cause.js";
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
import { BaseRenderer } from "./_base.mjs";
import { warn } from "../../../../helpers/console.mjs";
import { toSingleLine } from "../../../../helpers/templateLiteralTag.mjs";
import { OrderView } from "../utils/orderView/index.mjs";
import { addClass, hasClass, removeClass, setAttribute } from "../../../../helpers/dom/element.mjs";
import { A11Y_ROW, A11Y_ROWGROUP, A11Y_ROWINDEX } from "../../../../helpers/a11y.mjs";
const ROW_CLASSNAMES = {
  rowEven: 'ht__row_even',
  rowOdd: 'ht__row_odd'
};
let performanceWarningAppeared = false;

/**
 * Rows renderer responsible for managing (inserting, tracking, rendering) TR elements belongs to TBODY.
 *
 *   <tbody> (root node)
 *     ├ <tr>   \
 *     ├ <tr>    \
 *     ├ <tr>     - RowsRenderer
 *     ├ <tr>    /
 *     └ <tr>   /.
 *
 * @class {RowsRenderer}
 */
export class RowsRenderer extends BaseRenderer {
  constructor(rootNode) {
    super('TR', rootNode);
    /**
     * Cache for OrderView classes connected to specified node.
     *
     * @type {WeakMap}
     */
    _defineProperty(this, "orderView", void 0);
    this.orderView = new OrderView(rootNode, sourceRowIndex => this.nodesPool.obtain(sourceRowIndex));
  }

  /**
   * Returns currently rendered node.
   *
   * @param {string} visualIndex Visual index of the rendered node (it always goeas from 0 to N).
   * @returns {HTMLTableRowElement}
   */
  getRenderedNode(visualIndex) {
    return this.orderView.getNode(visualIndex);
  }

  /**
   * Checks if the the row is marked as "stale" and has to be rerendered.
   *
   * @param {number} visualIndex Visual index of the rendered node (it always goeas from 0 to N).
   * @returns {boolean}
   */
  hasStaleContent(visualIndex) {
    return this.orderView.hasStaleContent(visualIndex);
  }

  /**
   * Renders the cells.
   */
  render() {
    const {
      rowsToRender
    } = this.table;
    if (!performanceWarningAppeared && rowsToRender > 1000) {
      performanceWarningAppeared = true;
      warn(toSingleLine`Performance tip: Handsontable rendered more than 1000 visible rows.\x20
        Consider limiting the number of rendered rows by specifying the table height and/or\x20
        turning off the "renderAllRows" option.`);
    }
    if (this.table.isAriaEnabled()) {
      setAttribute(this.rootNode, [A11Y_ROWGROUP()]);
    }
    this.orderView.setSize(rowsToRender).setOffset(this.table.renderedRowToSource(0)).start();
    for (let visibleRowIndex = 0; visibleRowIndex < rowsToRender; visibleRowIndex++) {
      this.orderView.render();
      const TR = this.orderView.getCurrentNode();
      const sourceRowIndex = this.table.renderedRowToSource(visibleRowIndex);
      if (this.table.isAriaEnabled()) {
        var _this$table$rowUtils$, _this$table$rowUtils;
        setAttribute(TR, [A11Y_ROW(),
        // `aria-rowindex` is incremented by both tbody and thead rows.
        A11Y_ROWINDEX(sourceRowIndex + ((_this$table$rowUtils$ = (_this$table$rowUtils = this.table.rowUtils) === null || _this$table$rowUtils === void 0 || (_this$table$rowUtils = _this$table$rowUtils.dataAccessObject) === null || _this$table$rowUtils === void 0 ? void 0 : _this$table$rowUtils.columnHeaders.length) !== null && _this$table$rowUtils$ !== void 0 ? _this$table$rowUtils$ : 0) + 1)]);
      }
      if ((sourceRowIndex + 1) % 2 === 0) {
        if (!hasClass(TR, ROW_CLASSNAMES.rowEven)) {
          removeClass(TR, ROW_CLASSNAMES.rowOdd);
          addClass(TR, ROW_CLASSNAMES.rowEven);
        }
      } else if (!hasClass(TR, ROW_CLASSNAMES.rowOdd)) {
        removeClass(TR, ROW_CLASSNAMES.rowEven);
        addClass(TR, ROW_CLASSNAMES.rowOdd);
      }
    }
    this.orderView.end();
  }
}