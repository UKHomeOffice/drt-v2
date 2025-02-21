import "core-js/modules/es.error.cause.js";
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
import { SharedOrderView } from "../utils/orderView/index.mjs";
import { BaseRenderer } from "./_base.mjs";
import { setAttribute, removeAttribute } from "../../../../helpers/dom/element.mjs";
import { A11Y_COLINDEX, A11Y_ROWHEADER, A11Y_SCOPE_ROW, A11Y_TABINDEX } from "../../../../helpers/a11y.mjs";
/**
 * Row headers renderer responsible for managing (inserting, tracking, rendering) TR elements belongs to TR.
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
export class RowHeadersRenderer extends BaseRenderer {
  constructor() {
    super('TH');
    /**
     * Cache for OrderView classes connected to specified node.
     *
     * @type {WeakMap}
     */
    _defineProperty(this, "orderViews", new WeakMap());
    /**
     * Row index which specifies the row position of the processed row header.
     *
     * @type {number}
     */
    _defineProperty(this, "sourceRowIndex", 0);
  }

  /**
   * Obtains the instance of the SharedOrderView class which is responsible for rendering the nodes to the root node.
   *
   * @param {HTMLTableRowElement} rootNode The TR element, which is root element for row headers (TH).
   * @returns {SharedOrderView}
   */
  obtainOrderView(rootNode) {
    let orderView;
    if (this.orderViews.has(rootNode)) {
      orderView = this.orderViews.get(rootNode);
    } else {
      orderView = new SharedOrderView(rootNode, sourceColumnIndex => this.nodesPool.obtain(this.sourceRowIndex, sourceColumnIndex));
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
      rowHeaderFunctions,
      rowHeadersCount,
      rows,
      cells
    } = this.table;
    for (let visibleRowIndex = 0; visibleRowIndex < rowsToRender; visibleRowIndex++) {
      const sourceRowIndex = this.table.renderedRowToSource(visibleRowIndex);
      const TR = rows.getRenderedNode(visibleRowIndex);
      this.sourceRowIndex = sourceRowIndex;
      const orderView = this.obtainOrderView(TR);
      const cellsView = cells.obtainOrderView(TR);
      orderView.appendView(cellsView).setSize(rowHeadersCount).setOffset(0).start();
      for (let visibleColumnIndex = 0; visibleColumnIndex < rowHeadersCount; visibleColumnIndex++) {
        orderView.render();
        const TH = orderView.getCurrentNode();
        TH.className = '';
        TH.removeAttribute('style');

        // Remove all accessibility-related attributes for the header to start fresh.
        removeAttribute(TH, [new RegExp('aria-(.*)'), new RegExp('role')]);
        if (this.table.isAriaEnabled()) {
          setAttribute(TH, [A11Y_ROWHEADER(), A11Y_SCOPE_ROW(), A11Y_COLINDEX(visibleColumnIndex + 1), A11Y_TABINDEX(-1)]);
        }
        rowHeaderFunctions[visibleColumnIndex](sourceRowIndex, TH, visibleColumnIndex);
      }
      orderView.end();
    }
  }
}