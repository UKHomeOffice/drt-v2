"use strict";

require("core-js/modules/es.symbol");

require("core-js/modules/es.symbol.description");

require("core-js/modules/es.symbol.iterator");

require("core-js/modules/es.array.find");

require("core-js/modules/es.array.iterator");

require("core-js/modules/es.array.slice");

require("core-js/modules/es.function.name");

require("core-js/modules/es.object.freeze");

require("core-js/modules/es.object.get-prototype-of");

require("core-js/modules/es.object.set-prototype-of");

require("core-js/modules/es.object.to-string");

require("core-js/modules/es.string.iterator");

require("core-js/modules/web.dom-collections.iterator");

exports.__esModule = true;
exports.default = void 0;

var _element = require("../../../helpers/dom/element");

var _array = require("../../../helpers/array");

var _templateLiteralTag = require("../../../helpers/templateLiteralTag");

var _base = _interopRequireDefault(require("./_base"));

var _logicalOperationRegisterer = require("../logicalOperationRegisterer");

var _conjunction = require("../logicalOperations/conjunction");

var _disjunction = require("../logicalOperations/disjunction");

var _disjunctionWithExtraCondition = require("../logicalOperations/disjunctionWithExtraCondition");

var _radioInput = _interopRequireDefault(require("../ui/radioInput"));

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _typeof(obj) { if (typeof Symbol === "function" && typeof Symbol.iterator === "symbol") { _typeof = function _typeof(obj) { return typeof obj; }; } else { _typeof = function _typeof(obj) { return obj && typeof Symbol === "function" && obj.constructor === Symbol && obj !== Symbol.prototype ? "symbol" : typeof obj; }; } return _typeof(obj); }

function _templateObject() {
  var data = _taggedTemplateLiteral(["Radio button with index ", " doesn't exist."]);

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

var SELECTED_AT_START_ELEMENT_INDEX = 0;
/**
 * @class OperatorsComponent
 * @plugin Filters
 */

var OperatorsComponent =
/*#__PURE__*/
function (_BaseComponent) {
  _inherits(OperatorsComponent, _BaseComponent);

  function OperatorsComponent(hotInstance, options) {
    var _this;

    _classCallCheck(this, OperatorsComponent);

    _this = _possibleConstructorReturn(this, _getPrototypeOf(OperatorsComponent).call(this, hotInstance));
    _this.id = options.id;
    _this.name = options.name;

    _this.buildOperatorsElement();

    return _this;
  }
  /**
   * Get menu object descriptor.
   *
   * @returns {Object}
   */


  _createClass(OperatorsComponent, [{
    key: "getMenuItemDescriptor",
    value: function getMenuItemDescriptor() {
      var _this2 = this;

      return {
        key: this.id,
        name: this.name,
        isCommand: false,
        disableSelection: true,
        hidden: function hidden() {
          return _this2.isHidden();
        },
        renderer: function renderer(hot, wrapper) {
          (0, _element.addClass)(wrapper.parentNode, 'htFiltersMenuOperators');

          if (!wrapper.parentNode.hasAttribute('ghost-table')) {
            (0, _array.arrayEach)(_this2.elements, function (ui) {
              return wrapper.appendChild(ui.element);
            });
          }

          return wrapper;
        }
      };
    }
    /**
     * Add RadioInputUI elements to component
     *
     * @private
     */

  }, {
    key: "buildOperatorsElement",
    value: function buildOperatorsElement() {
      var _this3 = this;

      var operationKeys = [_conjunction.OPERATION_ID, _disjunction.OPERATION_ID];
      (0, _array.arrayEach)(operationKeys, function (operation) {
        var radioInput = new _radioInput.default(_this3.hot, {
          name: 'operator',
          label: {
            htmlFor: operation,
            textContent: (0, _logicalOperationRegisterer.getOperationName)(operation)
          },
          value: operation,
          checked: operation === operationKeys[SELECTED_AT_START_ELEMENT_INDEX],
          id: operation
        });
        radioInput.addLocalHook('change', function (event) {
          return _this3.onRadioInputChange(event);
        });

        _this3.elements.push(radioInput);
      });
    }
    /**
     * Set state of operators component to check radio input at specific `index`.
     *
     * @param searchedIndex Index of radio input to check.
     */

  }, {
    key: "setChecked",
    value: function setChecked(searchedIndex) {
      if (this.elements.length < searchedIndex) {
        throw Error((0, _templateLiteralTag.toSingleLine)(_templateObject(), searchedIndex));
      }

      (0, _array.arrayEach)(this.elements, function (element, index) {
        element.setChecked(index === searchedIndex);
      });
    }
    /**
     * Get `id` of active operator
     *
     * @returns {String}
     */

  }, {
    key: "getActiveOperationId",
    value: function getActiveOperationId() {
      var operationElement = this.elements.find(function (element) {
        return element instanceof _radioInput.default && element.isChecked();
      });

      if (operationElement) {
        return operationElement.getValue();
      }

      return _conjunction.OPERATION_ID;
    }
    /**
     * Export state of the component (get selected operator).
     *
     * @returns {String} Returns `id` of selected operator.
     */

  }, {
    key: "getState",
    value: function getState() {
      return this.getActiveOperationId();
    }
    /**
     * Set state of the component.
     *
     * @param {Object} value State to restore.
     */

  }, {
    key: "setState",
    value: function setState(value) {
      this.reset();

      if (value && this.getActiveOperationId() !== value) {
        (0, _array.arrayEach)(this.elements, function (element) {
          element.setChecked(element.getValue() === value);
        });
      }
    }
    /**
     * Update state of component.
     * @param [operationId='conjunction'] Id of selected operation.
     * @param column Physical column index.
     */

  }, {
    key: "updateState",
    value: function updateState() {
      var operationId = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : _conjunction.OPERATION_ID;
      var column = arguments.length > 1 ? arguments[1] : undefined;
      var selectedOperationId = operationId;

      if (selectedOperationId === _disjunctionWithExtraCondition.OPERATION_ID) {
        selectedOperationId = _disjunction.OPERATION_ID;
      }

      this.setCachedState(column, selectedOperationId);
    }
    /**
     * Reset elements to their initial state.
     */

  }, {
    key: "reset",
    value: function reset() {
      this.setChecked(SELECTED_AT_START_ELEMENT_INDEX);
    }
    /**
     * OnChange listener.
     *
     * @private
     * @param {Event} event DOM event
     */

  }, {
    key: "onRadioInputChange",
    value: function onRadioInputChange(event) {
      this.setState(event.realTarget.value);
    }
  }]);

  return OperatorsComponent;
}(_base.default);

var _default = OperatorsComponent;
exports.default = _default;