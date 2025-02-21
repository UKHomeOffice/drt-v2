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

var _element = require("./../../../../helpers/dom/element");

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
 * Column headers renderer responsible for managing (inserting, tracking, rendering) TR and TH elements.
 *
 *   <thead> (root node)
 *     ├ <tr>   \
 *     ├ <tr>    \
 *     ├ <tr>     - ColumnHeadersRenderer
 *     ├ <tr>    /
 *     └ <tr>   /
 *
 * @class {ColumnHeadersRenderer}
 */
var ColumnHeadersRenderer =
/*#__PURE__*/
function (_BaseRenderer) {
  _inherits(ColumnHeadersRenderer, _BaseRenderer);

  function ColumnHeadersRenderer(rootNode) {
    _classCallCheck(this, ColumnHeadersRenderer);

    return _possibleConstructorReturn(this, _getPrototypeOf(ColumnHeadersRenderer).call(this, null, rootNode)); // NodePool is not implemented for this renderer yet
  }
  /**
   * Adjusts the number of the rendered elements.
   */


  _createClass(ColumnHeadersRenderer, [{
    key: "adjust",
    value: function adjust() {
      var _this$table = this.table,
          columnHeadersCount = _this$table.columnHeadersCount,
          rowHeadersCount = _this$table.rowHeadersCount;
      var TR = this.rootNode.firstChild;

      if (columnHeadersCount) {
        var columnsToRender = this.table.columnsToRender;
        var allColumnsToRender = columnsToRender + rowHeadersCount;

        for (var i = 0, len = columnHeadersCount; i < len; i++) {
          TR = this.rootNode.childNodes[i];

          if (!TR) {
            TR = this.table.rootDocument.createElement('tr');
            this.rootNode.appendChild(TR);
          }

          this.renderedNodes = TR.childNodes.length;

          while (this.renderedNodes < allColumnsToRender) {
            TR.appendChild(this.table.rootDocument.createElement('th'));
            this.renderedNodes += 1;
          }

          while (this.renderedNodes > allColumnsToRender) {
            TR.removeChild(TR.lastChild);
            this.renderedNodes -= 1;
          }
        }

        var theadChildrenLength = this.rootNode.childNodes.length;

        if (theadChildrenLength > columnHeadersCount) {
          for (var _i = columnHeadersCount; _i < theadChildrenLength; _i++) {
            this.rootNode.removeChild(this.rootNode.lastChild);
          }
        }
      } else if (TR) {
        (0, _element.empty)(TR);
      }
    }
    /**
     * Renders the TH elements.
     */

  }, {
    key: "render",
    value: function render() {
      var columnHeadersCount = this.table.columnHeadersCount;

      for (var visibleRowIndex = 0; visibleRowIndex < columnHeadersCount; visibleRowIndex++) {
        var _this$table2 = this.table,
            columnHeaderFunctions = _this$table2.columnHeaderFunctions,
            columnsToRender = _this$table2.columnsToRender,
            rowHeadersCount = _this$table2.rowHeadersCount;
        var TR = this.rootNode.childNodes[visibleRowIndex];

        for (var renderedColumnIndex = -1 * rowHeadersCount; renderedColumnIndex < columnsToRender; renderedColumnIndex++) {
          var sourceColumnIndex = this.table.renderedColumnToSource(renderedColumnIndex);
          var TH = TR.childNodes[renderedColumnIndex + rowHeadersCount];
          TH.className = '';
          TH.removeAttribute('style');
          columnHeaderFunctions[visibleRowIndex](sourceColumnIndex, TH, visibleRowIndex);
        }
      }
    }
  }]);

  return ColumnHeadersRenderer;
}(_base.default);

exports.default = ColumnHeadersRenderer;