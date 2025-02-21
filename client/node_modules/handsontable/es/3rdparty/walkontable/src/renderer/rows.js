import "core-js/modules/es.symbol";
import "core-js/modules/es.symbol.description";
import "core-js/modules/es.symbol.iterator";
import "core-js/modules/es.array.iterator";
import "core-js/modules/es.array.slice";
import "core-js/modules/es.object.freeze";
import "core-js/modules/es.object.get-prototype-of";
import "core-js/modules/es.object.set-prototype-of";
import "core-js/modules/es.object.to-string";
import "core-js/modules/es.string.iterator";
import "core-js/modules/web.dom-collections.iterator";

function _typeof(obj) { if (typeof Symbol === "function" && typeof Symbol.iterator === "symbol") { _typeof = function _typeof(obj) { return typeof obj; }; } else { _typeof = function _typeof(obj) { return obj && typeof Symbol === "function" && obj.constructor === Symbol && obj !== Symbol.prototype ? "symbol" : typeof obj; }; } return _typeof(obj); }

function _templateObject() {
  var data = _taggedTemplateLiteral(["Performance tip: Handsontable rendered more than 1000 visible rows. Consider limiting the number \n        of rendered rows by specifying the table height and/or turning off the \"renderAllRows\" option."], ["Performance tip: Handsontable rendered more than 1000 visible rows. Consider limiting the number\\x20\n        of rendered rows by specifying the table height and/or turning off the \"renderAllRows\" option."]);

  _templateObject = function _templateObject() {
    return data;
  };

  return data;
}

function _taggedTemplateLiteral(strings, raw) { if (!raw) { raw = strings.slice(0); } return Object.freeze(Object.defineProperties(strings, { raw: { value: Object.freeze(raw) } })); }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

function _possibleConstructorReturn(self, call) { if (call && (_typeof(call) === "object" || typeof call === "function")) { return call; } return _assertThisInitialized(self); }

function _assertThisInitialized(self) { if (self === void 0) { throw new ReferenceError("this hasn't been initialised - super() hasn't been called"); } return self; }

function _getPrototypeOf(o) { _getPrototypeOf = Object.setPrototypeOf ? Object.getPrototypeOf : function _getPrototypeOf(o) { return o.__proto__ || Object.getPrototypeOf(o); }; return _getPrototypeOf(o); }

function _inherits(subClass, superClass) { if (typeof superClass !== "function" && superClass !== null) { throw new TypeError("Super expression must either be null or a function"); } subClass.prototype = Object.create(superClass && superClass.prototype, { constructor: { value: subClass, writable: true, configurable: true } }); if (superClass) _setPrototypeOf(subClass, superClass); }

function _setPrototypeOf(o, p) { _setPrototypeOf = Object.setPrototypeOf || function _setPrototypeOf(o, p) { o.__proto__ = p; return o; }; return _setPrototypeOf(o, p); }

import { warn } from './../../../../helpers/console';
import { toSingleLine } from './../../../../helpers/templateLiteralTag';
import { OrderView } from './../utils/orderView';
import BaseRenderer from './_base';
var performanceWarningAppeared = false;
/**
 * Rows renderer responsible for managing (inserting, tracking, rendering) TR elements belongs to TBODY.
 *
 *   <tbody> (root node)
 *     ├ <tr>   \
 *     ├ <tr>    \
 *     ├ <tr>     - RowsRenderer
 *     ├ <tr>    /
 *     └ <tr>   /
 *
 * @class {RowsRenderer}
 */

var RowsRenderer =
/*#__PURE__*/
function (_BaseRenderer) {
  _inherits(RowsRenderer, _BaseRenderer);

  function RowsRenderer(rootNode) {
    var _this;

    _classCallCheck(this, RowsRenderer);

    _this = _possibleConstructorReturn(this, _getPrototypeOf(RowsRenderer).call(this, 'TR', rootNode));
    /**
     * Cache for OrderView classes connected to specified node.
     *
     * @type {WeakMap}
     */

    _this.orderView = new OrderView(rootNode, function (sourceRowIndex) {
      return _this.nodesPool.obtain(sourceRowIndex);
    }, _this.nodeType);
    return _this;
  }
  /**
   * Returns currently rendered node.
   *
   * @param {String} visualIndex Visual index of the rendered node (it always goeas from 0 to N).
   * @return {HTMLTableRowElement}
   */


  _createClass(RowsRenderer, [{
    key: "getRenderedNode",
    value: function getRenderedNode(visualIndex) {
      return this.orderView.getNode(visualIndex);
    }
    /**
     * Renders the cells.
     */

  }, {
    key: "render",
    value: function render() {
      var rowsToRender = this.table.rowsToRender;

      if (!performanceWarningAppeared && rowsToRender > 1000) {
        performanceWarningAppeared = true;
        warn(toSingleLine(_templateObject()));
      }

      this.orderView.setSize(rowsToRender).setOffset(this.table.renderedRowToSource(0)).start();

      for (var visibleRowIndex = 0; visibleRowIndex < rowsToRender; visibleRowIndex++) {
        this.orderView.render();
      }

      this.orderView.end();
    }
  }]);

  return RowsRenderer;
}(BaseRenderer);

export { RowsRenderer as default };