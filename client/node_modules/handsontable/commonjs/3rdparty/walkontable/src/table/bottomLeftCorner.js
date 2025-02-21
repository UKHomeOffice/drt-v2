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

var _table = _interopRequireDefault(require("../table"));

var _stickyRowsBottom = _interopRequireDefault(require("./mixin/stickyRowsBottom"));

var _stickyColumnsLeft = _interopRequireDefault(require("./mixin/stickyColumnsLeft"));

var _object = require("./../../../../helpers/object");

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _typeof(obj) { if (typeof Symbol === "function" && typeof Symbol.iterator === "symbol") { _typeof = function _typeof(obj) { return typeof obj; }; } else { _typeof = function _typeof(obj) { return obj && typeof Symbol === "function" && obj.constructor === Symbol && obj !== Symbol.prototype ? "symbol" : typeof obj; }; } return _typeof(obj); }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _possibleConstructorReturn(self, call) { if (call && (_typeof(call) === "object" || typeof call === "function")) { return call; } return _assertThisInitialized(self); }

function _assertThisInitialized(self) { if (self === void 0) { throw new ReferenceError("this hasn't been initialised - super() hasn't been called"); } return self; }

function _getPrototypeOf(o) { _getPrototypeOf = Object.setPrototypeOf ? Object.getPrototypeOf : function _getPrototypeOf(o) { return o.__proto__ || Object.getPrototypeOf(o); }; return _getPrototypeOf(o); }

function _inherits(subClass, superClass) { if (typeof superClass !== "function" && superClass !== null) { throw new TypeError("Super expression must either be null or a function"); } subClass.prototype = Object.create(superClass && superClass.prototype, { constructor: { value: subClass, writable: true, configurable: true } }); if (superClass) _setPrototypeOf(subClass, superClass); }

function _setPrototypeOf(o, p) { _setPrototypeOf = Object.setPrototypeOf || function _setPrototypeOf(o, p) { o.__proto__ = p; return o; }; return _setPrototypeOf(o, p); }

/**
 * Subclass of `Table` that provides the helper methods relevant to BottomLeftCornerOverlay, implemented through mixins.
 */
var BottomLeftCornerOverlayTable =
/*#__PURE__*/
function (_Table) {
  _inherits(BottomLeftCornerOverlayTable, _Table);

  function BottomLeftCornerOverlayTable() {
    _classCallCheck(this, BottomLeftCornerOverlayTable);

    return _possibleConstructorReturn(this, _getPrototypeOf(BottomLeftCornerOverlayTable).apply(this, arguments));
  }

  return BottomLeftCornerOverlayTable;
}(_table.default);

(0, _object.mixin)(BottomLeftCornerOverlayTable, _stickyRowsBottom.default);
(0, _object.mixin)(BottomLeftCornerOverlayTable, _stickyColumnsLeft.default);
var _default = BottomLeftCornerOverlayTable;
exports.default = _default;