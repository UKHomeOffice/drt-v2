"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
require("core-js/modules/es.array.push.js");
require("core-js/modules/esnext.iterator.constructor.js");
require("core-js/modules/esnext.iterator.find.js");
var _element = require("../../../helpers/dom/element");
var _array = require("../../../helpers/array");
var _templateLiteralTag = require("../../../helpers/templateLiteralTag");
var _base = require("./_base");
var _logicalOperationRegisterer = require("../logicalOperationRegisterer");
var _conjunction = require("../logicalOperations/conjunction");
var _disjunction = require("../logicalOperations/disjunction");
var _disjunctionWithExtraCondition = require("../logicalOperations/disjunctionWithExtraCondition");
var _radioInput = require("../ui/radioInput");
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
const SELECTED_AT_START_ELEMENT_INDEX = 0;

/**
 * @private
 * @class OperatorsComponent
 */
var _OperatorsComponent_brand = /*#__PURE__*/new WeakSet();
class OperatorsComponent extends _base.BaseComponent {
  constructor(hotInstance, options) {
    super(hotInstance, {
      id: options.id,
      stateless: false
    });
    /**
     * OnChange listener.
     *
     * @param {Event} event The DOM event object.
     */
    _classPrivateMethodInitSpec(this, _OperatorsComponent_brand);
    /**
     * The name of the component.
     *
     * @type {string}
     */
    _defineProperty(this, "name", '');
    this.name = options.name;
    this.buildOperatorsElement();
  }

  /**
   * Get menu object descriptor.
   *
   * @returns {object}
   */
  getMenuItemDescriptor() {
    return {
      key: this.id,
      name: this.name,
      isCommand: false,
      disableSelection: true,
      hidden: () => this.isHidden(),
      renderer: (hot, wrapper) => {
        (0, _element.addClass)(wrapper.parentNode, 'htFiltersMenuOperators');
        (0, _array.arrayEach)(this.elements, ui => wrapper.appendChild(ui.element));
        return wrapper;
      }
    };
  }

  /**
   * Add RadioInputUI elements to component.
   *
   * @private
   */
  buildOperatorsElement() {
    const operationKeys = [_conjunction.OPERATION_ID, _disjunction.OPERATION_ID];
    (0, _array.arrayEach)(operationKeys, operation => {
      const radioInput = new _radioInput.RadioInputUI(this.hot, {
        name: 'operator',
        label: {
          htmlFor: operation,
          textContent: (0, _logicalOperationRegisterer.getOperationName)(operation)
        },
        value: operation,
        checked: operation === operationKeys[SELECTED_AT_START_ELEMENT_INDEX],
        id: operation
      });
      radioInput.addLocalHook('change', event => _assertClassBrand(_OperatorsComponent_brand, this, _onRadioInputChange).call(this, event));
      this.elements.push(radioInput);
    });
  }

  /**
   * Set state of operators component to check radio input at specific `index`.
   *
   * @param {number} searchedIndex Index of radio input to check.
   */
  setChecked(searchedIndex) {
    if (this.elements.length < searchedIndex) {
      throw Error((0, _templateLiteralTag.toSingleLine)`Radio button with index ${searchedIndex} doesn't exist.`);
    }
    (0, _array.arrayEach)(this.elements, (element, index) => {
      element.setChecked(index === searchedIndex);
    });
  }

  /**
   * Get `id` of active operator.
   *
   * @returns {string}
   */
  getActiveOperationId() {
    const operationElement = this.elements.find(element => element instanceof _radioInput.RadioInputUI && element.isChecked());
    if (operationElement) {
      return operationElement.getValue();
    }
    return _conjunction.OPERATION_ID;
  }

  /**
   * Export state of the component (get selected operator).
   *
   * @returns {string} Returns `id` of selected operator.
   */
  getState() {
    return this.getActiveOperationId();
  }

  /**
   * Set state of the component.
   *
   * @param {object} value State to restore.
   */
  setState(value) {
    this.reset();
    if (value && this.getActiveOperationId() !== value) {
      (0, _array.arrayEach)(this.elements, element => {
        element.setChecked(element.getValue() === value);
      });
    }
  }

  /**
   * Update state of component.
   *
   * @param {string} [operationId='conjunction'] Id of selected operation.
   * @param {number} column Physical column index.
   */
  updateState() {
    let operationId = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : _conjunction.OPERATION_ID;
    let column = arguments.length > 1 ? arguments[1] : undefined;
    let selectedOperationId = operationId;
    if (selectedOperationId === _disjunctionWithExtraCondition.OPERATION_ID) {
      selectedOperationId = _disjunction.OPERATION_ID;
    }
    this.state.setValueAtIndex(column, selectedOperationId);
  }

  /**
   * Reset elements to their initial state.
   */
  reset() {
    this.setChecked(SELECTED_AT_START_ELEMENT_INDEX);
  }
}
exports.OperatorsComponent = OperatorsComponent;
function _onRadioInputChange(event) {
  this.setState(event.target.value);
}