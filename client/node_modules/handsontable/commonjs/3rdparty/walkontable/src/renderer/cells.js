"use strict";

require("core-js/modules/es.symbol");

require("core-js/modules/es.symbol.description");

require("core-js/modules/es.symbol.iterator");

require("core-js/modules/es.array.iterator");

require("core-js/modules/es.object.get-prototype-of");

require("core-js/modules/es.object.set-prototype-of");

require("core-js/modules/es.object.to-string");

require("core-js/modules/es.string.iterator");

require("core-js/modules/es.weak-map");

require("core-js/modules/web.dom-collections.iterator");

exports.__esModule = true;
exports.default = void 0;

var _element = require("./../../../../helpers/dom/element");

var _orderView = require("./../utils/orderView");

var _base = _interopRequireDefault(require("./_base"));

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _typeof(obj) { if (typeof Symbol === "function" && typeof Symbol.iterator === "symbol") { _typeof = function _typeof(obj) { return typeof obj; }; } else { _typeof = function _typeof(obj) { return obj && typeof Symbol === "function" && obj.constructor === Symbol && obj !== Symbol.prototype ? "symbol" : typeof obj; }; } return _typeof(obj); }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

function _possibleConstructorReturn(self, call) { if (call && (_typeof(call) === "object" || typeof call === "function")) { return call; } return _assertThisInitialized(self); }

function _assertThisInitialized(self) { if (self === void 0) { throw new ReferenceError("this hasn't been initialised - super() hasn't been called"); } return self; }

function _getPrototypeOf(o) { _getPrototypeOf = Object.setPrototypeOf ? Object.getPrototypeOf : function _getPrototypeOf(o) { return o.__proto__ || Object.getPrototypeOf(o); }; return _getPrototypeOf(o); }

function _inherits(subClass, superClass) { if (typeof superClass !== "function" && superClass !== null) { throw new TypeError("Super expression must either be null or a function"); } subClass.prototype = Object.create(superClass && superClass.prototype, { constructor: { value: subClass, writable: true, configurable: true } }); if (superClass) _setPrototypeOf(subClass, superClass); }

function _setPrototypeOf(o, p) { _setPrototypeOf = Object.setPrototypeOf || function _setPrototypeOf(o, p) { o.__proto__ = p; return o; }; return _setPrototypeOf(o, p); }

/**
 * Cell renderer responsible for managing (inserting, tracking, rendering) TD elements.
 *
 *   <tr> (root node)
 *     ├ <th>   --- RowHeadersRenderer
 *     ├ <td>   \
 *     ├ <td>    \
 *     ├ <td>     - CellsRenderer
 *     ├ <td>    /
 *     └ <td>   /
 *
 * @class {CellsRenderer}
 */
var CellsRenderer =
/*#__PURE__*/
function (_BaseRenderer) {
  _inherits(CellsRenderer, _BaseRenderer);

  function CellsRenderer() {
    var _this;

    _classCallCheck(this, CellsRenderer);

    _this = _possibleConstructorReturn(this, _getPrototypeOf(CellsRenderer).call(this, 'TD'));
    /**
     * Cache for OrderView classes connected to specified node.
     *
     * @type {WeakMap}
     */

    _this.orderViews = new WeakMap();
    /**
     * Row index which specifies the row position of the processed cell.
     *
     * @type {Number}
     */

    _this.sourceRowIndex = 0;
    return _this;
  }
  /**
   * Obtains the instance of the SharedOrderView class which is responsible for rendering the nodes to the root node.
   *
   * @param {HTMLTableRowElement} rootNode The TR element, which is root element for cells (TD).
   * @return {SharedOrderView}
   */


  _createClass(CellsRenderer, [{
    key: "obtainOrderView",
    value: function obtainOrderView(rootNode) {
      var _this2 = this;

      var orderView;

      if (this.orderViews.has(rootNode)) {
        orderView = this.orderViews.get(rootNode);
      } else {
        orderView = new _orderView.SharedOrderView(rootNode, function (sourceColumnIndex) {
          return _this2.nodesPool.obtain(_this2.sourceRowIndex, sourceColumnIndex);
        }, this.nodeType);
        this.orderViews.set(rootNode, orderView);
      }

      return orderView;
    }
    /**
     * Renders the cells.
     */

  }, {
    key: "render",
    value: function render() {
      var _this$table = this.table,
          rowsToRender = _this$table.rowsToRender,
          columnsToRender = _this$table.columnsToRender,
          rows = _this$table.rows,
          rowHeaders = _this$table.rowHeaders;

      for (var visibleRowIndex = 0; visibleRowIndex < rowsToRender; visibleRowIndex++) {
        var sourceRowIndex = this.table.renderedRowToSource(visibleRowIndex);
        var TR = rows.getRenderedNode(visibleRowIndex);
        this.sourceRowIndex = sourceRowIndex;
        var orderView = this.obtainOrderView(TR);
        var rowHeadersView = rowHeaders.obtainOrderView(TR); // @TODO(perf-tip): For cells other than "visual 0" generating diff leads/commands can be skipped. New order view
        // shoule share state between next orderViews.

        orderView.prependView(rowHeadersView).setSize(columnsToRender).setOffset(this.table.renderedColumnToSource(0)).start();

        for (var visibleColumnIndex = 0; visibleColumnIndex < columnsToRender; visibleColumnIndex++) {
          orderView.render();
          var TD = orderView.getCurrentNode();
          var sourceColumnIndex = this.table.renderedColumnToSource(visibleColumnIndex);

          if (!(0, _element.hasClass)(TD, 'hide')) {
            // Workaround for hidden columns plugin
            TD.className = '';
          }

          TD.removeAttribute('style');
          this.table.cellRenderer(sourceRowIndex, sourceColumnIndex, TD);
        }

        orderView.end();
      }
    }
  }]);

  return CellsRenderer;
}(_base.default);

exports.default = CellsRenderer;