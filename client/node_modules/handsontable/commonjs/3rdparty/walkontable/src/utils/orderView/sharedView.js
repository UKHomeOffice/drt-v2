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

var _view = _interopRequireDefault(require("./view"));

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
 * Executive model for TR root nodes.
 *
 * @class {SharedOrderView}
 */
var SharedOrderView =
/*#__PURE__*/
function (_OrderView) {
  _inherits(SharedOrderView, _OrderView);

  function SharedOrderView() {
    _classCallCheck(this, SharedOrderView);

    return _possibleConstructorReturn(this, _getPrototypeOf(SharedOrderView).apply(this, arguments));
  }

  _createClass(SharedOrderView, [{
    key: "prependView",

    /**
     * The method results in merging external order view into the current order. This happens only for order views which
     * operate on the same root node.
     *
     * In the table, there is only one scenario when this happens. TR root element
     * has a common root node with cells order view and row headers order view. Both classes have to share
     * information about their order sizes to make proper diff calculations.
     *
     * @param {OrderView} orderView The order view to merging with. The view will be added at the beginning of the list.
     * @returns {SharedOrderView}
     */
    value: function prependView(orderView) {
      this.sizeSet.prepend(orderView.sizeSet);
      orderView.sizeSet.append(this.sizeSet);
      return this;
    }
    /**
     * The method results in merging external order view into the current order. This happens only for order views which
     * operate on the same root node.
     *
     * In the table, there is only one scenario when this happens. TR root element
     * has a common root node with cells order view and row headers order view. Both classes have to share
     * information about their order sizes to make proper diff calculations.
     *
     * @param {OrderView} orderView The order view to merging with. The view will be added at the end of the list.
     * @returns {SharedOrderView}
     */

  }, {
    key: "appendView",
    value: function appendView(orderView) {
      this.sizeSet.append(orderView.sizeSet);
      orderView.sizeSet.prepend(this.sizeSet);
      return this;
    }
  }]);

  return SharedOrderView;
}(_view.default);

exports.default = SharedOrderView;