"use strict";

require("core-js/modules/es.symbol");

require("core-js/modules/es.symbol.description");

require("core-js/modules/es.symbol.iterator");

require("core-js/modules/es.array.iterator");

require("core-js/modules/es.object.get-prototype-of");

require("core-js/modules/es.object.set-prototype-of");

require("core-js/modules/es.object.to-string");

require("core-js/modules/es.string.iterator");

require("core-js/modules/web.dom-collections.iterator");

exports.__esModule = true;
exports.default = void 0;

var _base = _interopRequireDefault(require("./_base"));

var _element = require("./../../../../helpers/dom/element");

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
 * Colgroup renderer responsible for managing (inserting, tracking, rendering) COL elements.
 *
 *   <colgroup> (root node)
 *     ├ <col>   \
 *     ├ <col>    \
 *     ├ <col>     - ColGroupRenderer
 *     ├ <col>    /
 *     └ <col>   /
 *
 * @class {ColGroupRenderer}
 */
var ColGroupRenderer =
/*#__PURE__*/
function (_BaseRenderer) {
  _inherits(ColGroupRenderer, _BaseRenderer);

  function ColGroupRenderer(rootNode) {
    _classCallCheck(this, ColGroupRenderer);

    return _possibleConstructorReturn(this, _getPrototypeOf(ColGroupRenderer).call(this, null, rootNode)); // NodePool is not implemented for this renderer yet
  }
  /**
   * Adjusts the number of the rendered elements.
   */


  _createClass(ColGroupRenderer, [{
    key: "adjust",
    value: function adjust() {
      var _this$table = this.table,
          columnsToRender = _this$table.columnsToRender,
          rowHeadersCount = _this$table.rowHeadersCount;
      var allColumnsToRender = columnsToRender + rowHeadersCount;

      while (this.renderedNodes < allColumnsToRender) {
        this.rootNode.appendChild(this.table.rootDocument.createElement('col'));
        this.renderedNodes += 1;
      }

      while (this.renderedNodes > allColumnsToRender) {
        this.rootNode.removeChild(this.rootNode.lastChild);
        this.renderedNodes -= 1;
      }
    }
    /**
     * Renders the col group elements.
     */

  }, {
    key: "render",
    value: function render() {
      this.adjust();
      var _this$table2 = this.table,
          columnsToRender = _this$table2.columnsToRender,
          rowHeadersCount = _this$table2.rowHeadersCount; // Render column nodes for row headers

      for (var visibleColumnIndex = 0; visibleColumnIndex < rowHeadersCount; visibleColumnIndex++) {
        var sourceColumnIndex = this.table.renderedColumnToSource(visibleColumnIndex);
        var width = this.table.columnUtils.getHeaderWidth(sourceColumnIndex);
        this.rootNode.childNodes[visibleColumnIndex].style.width = "".concat(width, "px");
      } // Render column nodes for cells


      for (var _visibleColumnIndex = 0; _visibleColumnIndex < columnsToRender; _visibleColumnIndex++) {
        var _sourceColumnIndex = this.table.renderedColumnToSource(_visibleColumnIndex);

        var _width = this.table.columnUtils.getStretchedColumnWidth(_sourceColumnIndex);

        this.rootNode.childNodes[_visibleColumnIndex + rowHeadersCount].style.width = "".concat(_width, "px");
      }

      var firstChild = this.rootNode.firstChild;

      if (firstChild) {
        (0, _element.addClass)(firstChild, 'rowHeader');
      }
    }
  }]);

  return ColGroupRenderer;
}(_base.default);

exports.default = ColGroupRenderer;