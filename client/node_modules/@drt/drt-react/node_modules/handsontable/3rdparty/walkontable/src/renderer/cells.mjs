import "core-js/modules/es.error.cause.js";
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
import { hasClass, removeAttribute, setAttribute } from "../../../../helpers/dom/element.mjs";
import { SharedOrderView } from "../utils/orderView/index.mjs";
import { BaseRenderer } from "./_base.mjs";
import { A11Y_COLINDEX, A11Y_GRIDCELL, A11Y_TABINDEX } from "../../../../helpers/a11y.mjs";
/**
 * Cell renderer responsible for managing (inserting, tracking, rendering) TD elements.
 *
 *   <tr> (root node)
 *     ├ <th>   --- RowHeadersRenderer
 *     ├ <td>   \
 *     ├ <td>    \
 *     ├ <td>     - CellsRenderer
 *     ├ <td>    /
 *     └ <td>   /.
 *
 * @class {CellsRenderer}
 */
export class CellsRenderer extends BaseRenderer {
  constructor() {
    super('TD');
    /**
     * Cache for OrderView classes connected to specified node.
     *
     * @type {WeakMap}
     */
    _defineProperty(this, "orderViews", new WeakMap());
    /**
     * Row index which specifies the row position of the processed cell.
     *
     * @type {number}
     */
    _defineProperty(this, "sourceRowIndex", 0);
  }

  /**
   * Obtains the instance of the SharedOrderView class which is responsible for rendering the nodes to the root node.
   *
   * @param {HTMLTableRowElement} rootNode The TR element, which is root element for cells (TD).
   * @returns {SharedOrderView}
   */
  obtainOrderView(rootNode) {
    let orderView;
    if (this.orderViews.has(rootNode)) {
      orderView = this.orderViews.get(rootNode);
    } else {
      orderView = new SharedOrderView(rootNode, sourceColumnIndex => this.nodesPool.obtain(this.sourceRowIndex, sourceColumnIndex), this.nodeType);
      this.orderViews.set(rootNode, orderView);
    }
    return orderView;
  }

  /**
   * Renders the cells.
   */
  render() {
    const {
      rowsToRender,
      columnsToRender,
      rows,
      rowHeaders
    } = this.table;
    for (let visibleRowIndex = 0; visibleRowIndex < rowsToRender; visibleRowIndex++) {
      const sourceRowIndex = this.table.renderedRowToSource(visibleRowIndex);
      const TR = rows.getRenderedNode(visibleRowIndex);
      this.sourceRowIndex = sourceRowIndex;
      const orderView = this.obtainOrderView(TR);
      const rowHeadersView = rowHeaders.obtainOrderView(TR);
      orderView.prependView(rowHeadersView).setSize(columnsToRender).setOffset(0).start();
      for (let visibleColumnIndex = 0; visibleColumnIndex < columnsToRender; visibleColumnIndex++) {
        orderView.render();
        const sourceColumnIndex = this.table.renderedColumnToSource(visibleColumnIndex);
        const TD = orderView.getCurrentNode();
        if (!hasClass(TD, 'hide')) {
          // Workaround for hidden columns plugin
          TD.className = '';
        }
        TD.removeAttribute('style');
        TD.removeAttribute('dir');

        // Remove all accessibility-related attributes for the cell to start fresh.
        removeAttribute(TD, [new RegExp('aria-(.*)'), new RegExp('role')]);
        this.table.cellRenderer(sourceRowIndex, sourceColumnIndex, TD);
        if (this.table.isAriaEnabled()) {
          var _this$table$rowUtils$, _this$table$rowUtils;
          setAttribute(TD, [...(TD.hasAttribute('role') ? [] : [A11Y_GRIDCELL()]), A11Y_TABINDEX(-1),
          // `aria-colindex` is incremented by both tbody and thead rows.
          A11Y_COLINDEX(sourceColumnIndex + ((_this$table$rowUtils$ = (_this$table$rowUtils = this.table.rowUtils) === null || _this$table$rowUtils === void 0 || (_this$table$rowUtils = _this$table$rowUtils.dataAccessObject) === null || _this$table$rowUtils === void 0 ? void 0 : _this$table$rowUtils.rowHeaders.length) !== null && _this$table$rowUtils$ !== void 0 ? _this$table$rowUtils$ : 0) + 1)]);
        }
      }
      orderView.end();
    }
  }
}