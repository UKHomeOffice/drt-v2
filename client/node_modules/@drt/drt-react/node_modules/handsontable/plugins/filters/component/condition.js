"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
require("core-js/modules/es.array.push.js");
require("core-js/modules/esnext.iterator.constructor.js");
require("core-js/modules/esnext.iterator.filter.js");
var _element = require("../../../helpers/dom/element");
var _event = require("../../../helpers/dom/event");
var _array = require("../../../helpers/array");
var _unicode = require("../../../helpers/unicode");
var _object = require("../../../helpers/object");
var C = _interopRequireWildcard(require("../../../i18n/constants"));
var _base = require("./_base");
var _constants2 = _interopRequireWildcard(require("../constants"));
var _input = require("../ui/input");
var _select = require("../ui/select");
var _conditionRegisterer = require("../conditionRegisterer");
function _getRequireWildcardCache(e) { if ("function" != typeof WeakMap) return null; var r = new WeakMap(), t = new WeakMap(); return (_getRequireWildcardCache = function (e) { return e ? t : r; })(e); }
function _interopRequireWildcard(e, r) { if (!r && e && e.__esModule) return e; if (null === e || "object" != typeof e && "function" != typeof e) return { default: e }; var t = _getRequireWildcardCache(r); if (t && t.has(e)) return t.get(e); var n = { __proto__: null }, a = Object.defineProperty && Object.getOwnPropertyDescriptor; for (var u in e) if ("default" !== u && {}.hasOwnProperty.call(e, u)) { var i = a ? Object.getOwnPropertyDescriptor(e, u) : null; i && (i.get || i.set) ? Object.defineProperty(n, u, i) : n[u] = e[u]; } return n.default = e, t && t.set(e, n), n; }
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
/**
 * @private
 * @class ConditionComponent
 */
var _ConditionComponent_brand = /*#__PURE__*/new WeakSet();
class ConditionComponent extends _base.BaseComponent {
  constructor(hotInstance, options) {
    super(hotInstance, {
      id: options.id,
      stateless: false
    });
    /**
     * On condition select listener.
     *
     * @param {object} command Menu item object (command).
     */
    _classPrivateMethodInitSpec(this, _ConditionComponent_brand);
    /**
     * The name of the component.
     *
     * @type {string}
     */
    _defineProperty(this, "name", '');
    /**
     * @type {boolean}
     */
    _defineProperty(this, "addSeparator", false);
    this.name = options.name;
    this.addSeparator = options.addSeparator;
    this.elements.push(new _select.SelectUI(this.hot, {
      menuContainer: options.menuContainer
    }));
    this.elements.push(new _input.InputUI(this.hot, {
      placeholder: C.FILTERS_BUTTONS_PLACEHOLDER_VALUE
    }));
    this.elements.push(new _input.InputUI(this.hot, {
      placeholder: C.FILTERS_BUTTONS_PLACEHOLDER_SECOND_VALUE
    }));
    this.registerHooks();
  }

  /**
   * Register all necessary hooks.
   *
   * @private
   */
  registerHooks() {
    this.getSelectElement().addLocalHook('select', command => _assertClassBrand(_ConditionComponent_brand, this, _onConditionSelect).call(this, command)).addLocalHook('afterClose', () => this.runLocalHooks('afterClose')).addLocalHook('tabKeydown', event => this.runLocalHooks('selectTabKeydown', event));
    (0, _array.arrayEach)(this.getInputElements(), input => {
      input.addLocalHook('keydown', event => _assertClassBrand(_ConditionComponent_brand, this, _onInputKeyDown).call(this, event));
    });
  }

  /**
   * Set state of the component.
   *
   * @param {object} value State to restore.
   */
  setState(value) {
    this.reset();
    if (!value) {
      return;
    }
    const copyOfCommand = (0, _object.clone)(value.command);
    if (copyOfCommand.name.startsWith(C.FILTERS_CONDITIONS_NAMESPACE)) {
      copyOfCommand.name = this.hot.getTranslatedPhrase(copyOfCommand.name);
    }
    this.getSelectElement().setValue(copyOfCommand);
    (0, _array.arrayEach)(value.args, (arg, index) => {
      if (index > copyOfCommand.inputsCount - 1) {
        return false;
      }
      const element = this.getInputElement(index);
      element.setValue(arg);
      element[copyOfCommand.inputsCount > index ? 'show' : 'hide']();
      if (!index) {
        this.hot._registerTimeout(() => element.focus(), 10);
      }
    });
  }

  /**
   * Export state of the component (get selected filter and filter arguments).
   *
   * @returns {object} Returns object where `command` key keeps used condition filter and `args` key its arguments.
   */
  getState() {
    const command = this.getSelectElement().getValue() || (0, _conditionRegisterer.getConditionDescriptor)(_constants2.CONDITION_NONE);
    const args = [];
    (0, _array.arrayEach)(this.getInputElements(), (element, index) => {
      if (command.inputsCount > index) {
        args.push(element.getValue());
      }
    });
    return {
      command,
      args
    };
  }

  /**
   * Update state of component.
   *
   * @param {object} condition The condition object.
   * @param {object} condition.command The command object with condition name as `key` property.
   * @param {Array} condition.args An array of values to compare.
   * @param {number} column Physical column index.
   */
  updateState(condition, column) {
    const command = condition ? (0, _conditionRegisterer.getConditionDescriptor)(condition.name) : (0, _conditionRegisterer.getConditionDescriptor)(_constants2.CONDITION_NONE);
    this.state.setValueAtIndex(column, {
      command,
      args: condition ? condition.args : []
    });
    if (!condition) {
      (0, _array.arrayEach)(this.getInputElements(), element => element.setValue(null));
    }
  }

  /**
   * Get select element.
   *
   * @returns {SelectUI}
   */
  getSelectElement() {
    return this.elements.filter(element => element instanceof _select.SelectUI)[0];
  }

  /**
   * Get input element.
   *
   * @param {number} index Index an array of elements.
   * @returns {InputUI}
   */
  getInputElement() {
    let index = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : 0;
    return this.getInputElements()[index];
  }

  /**
   * Get input elements.
   *
   * @returns {Array}
   */
  getInputElements() {
    return this.elements.filter(element => element instanceof _input.InputUI);
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
      renderer: (hot, wrapper, row, col, prop, value) => {
        (0, _element.addClass)(wrapper.parentNode, 'htFiltersMenuCondition');
        if (this.addSeparator) {
          (0, _element.addClass)(wrapper.parentNode, 'border');
        }
        const label = this.hot.rootDocument.createElement('div');
        (0, _element.addClass)(label, 'htFiltersMenuLabel');
        label.textContent = value;
        wrapper.appendChild(label);

        // The SelectUI should not extend the menu width (it should adjust to the menu item width only).
        // That's why it's skipped from rendering when the GhostTable tries to render it.
        if (!wrapper.parentElement.hasAttribute('ghost-table')) {
          (0, _array.arrayEach)(this.elements, ui => wrapper.appendChild(ui.element));
        }
        return wrapper;
      }
    };
  }

  /**
   * Reset elements to their initial state.
   */
  reset() {
    const selectedColumn = this.hot.getPlugin('filters').getSelectedColumn();
    let items = [(0, _conditionRegisterer.getConditionDescriptor)(_constants2.CONDITION_NONE)];
    if (selectedColumn !== null) {
      const {
        visualIndex
      } = selectedColumn;
      items = (0, _constants2.default)(this.hot.getDataType(0, visualIndex, this.hot.countRows(), visualIndex));
    }
    (0, _array.arrayEach)(this.getInputElements(), element => element.hide());
    this.getSelectElement().setItems(items);
    super.reset();
    // Select element as default 'None'
    this.getSelectElement().setValue(items[0]);
  }
}
exports.ConditionComponent = ConditionComponent;
function _onConditionSelect(command) {
  (0, _array.arrayEach)(this.getInputElements(), (element, index) => {
    element[command.inputsCount > index ? 'show' : 'hide']();
    if (index === 0) {
      this.hot._registerTimeout(() => element.focus(), 10);
    }
  });
  this.runLocalHooks('change', command);
}
/**
 * Key down listener.
 *
 * @param {Event} event The DOM event object.
 */
function _onInputKeyDown(event) {
  if ((0, _unicode.isKey)(event.keyCode, 'ESCAPE')) {
    this.runLocalHooks('cancel');
    (0, _event.stopImmediatePropagation)(event);
  }
}