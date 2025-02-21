import "core-js/modules/es.symbol";
import "core-js/modules/es.symbol.description";
import "core-js/modules/es.symbol.iterator";
import "core-js/modules/es.array.filter";
import "core-js/modules/es.array.iterator";
import "core-js/modules/es.function.name";
import "core-js/modules/es.object.get-own-property-descriptor";
import "core-js/modules/es.object.get-prototype-of";
import "core-js/modules/es.object.set-prototype-of";
import "core-js/modules/es.object.to-string";
import "core-js/modules/es.reflect.get";
import "core-js/modules/es.regexp.to-string";
import "core-js/modules/es.string.iterator";
import "core-js/modules/web.dom-collections.iterator";

function _typeof(obj) { if (typeof Symbol === "function" && typeof Symbol.iterator === "symbol") { _typeof = function _typeof(obj) { return typeof obj; }; } else { _typeof = function _typeof(obj) { return obj && typeof Symbol === "function" && obj.constructor === Symbol && obj !== Symbol.prototype ? "symbol" : typeof obj; }; } return _typeof(obj); }

function _slicedToArray(arr, i) { return _arrayWithHoles(arr) || _iterableToArrayLimit(arr, i) || _nonIterableRest(); }

function _nonIterableRest() { throw new TypeError("Invalid attempt to destructure non-iterable instance"); }

function _iterableToArrayLimit(arr, i) { if (!(Symbol.iterator in Object(arr) || Object.prototype.toString.call(arr) === "[object Arguments]")) { return; } var _arr = []; var _n = true; var _d = false; var _e = undefined; try { for (var _i = arr[Symbol.iterator](), _s; !(_n = (_s = _i.next()).done); _n = true) { _arr.push(_s.value); if (i && _arr.length === i) break; } } catch (err) { _d = true; _e = err; } finally { try { if (!_n && _i["return"] != null) _i["return"](); } finally { if (_d) throw _e; } } return _arr; }

function _arrayWithHoles(arr) { if (Array.isArray(arr)) return arr; }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

function _possibleConstructorReturn(self, call) { if (call && (_typeof(call) === "object" || typeof call === "function")) { return call; } return _assertThisInitialized(self); }

function _assertThisInitialized(self) { if (self === void 0) { throw new ReferenceError("this hasn't been initialised - super() hasn't been called"); } return self; }

function _get(target, property, receiver) { if (typeof Reflect !== "undefined" && Reflect.get) { _get = Reflect.get; } else { _get = function _get(target, property, receiver) { var base = _superPropBase(target, property); if (!base) return; var desc = Object.getOwnPropertyDescriptor(base, property); if (desc.get) { return desc.get.call(receiver); } return desc.value; }; } return _get(target, property, receiver || target); }

function _superPropBase(object, property) { while (!Object.prototype.hasOwnProperty.call(object, property)) { object = _getPrototypeOf(object); if (object === null) break; } return object; }

function _getPrototypeOf(o) { _getPrototypeOf = Object.setPrototypeOf ? Object.getPrototypeOf : function _getPrototypeOf(o) { return o.__proto__ || Object.getPrototypeOf(o); }; return _getPrototypeOf(o); }

function _inherits(subClass, superClass) { if (typeof superClass !== "function" && superClass !== null) { throw new TypeError("Super expression must either be null or a function"); } subClass.prototype = Object.create(superClass && superClass.prototype, { constructor: { value: subClass, writable: true, configurable: true } }); if (superClass) _setPrototypeOf(subClass, superClass); }

function _setPrototypeOf(o, p) { _setPrototypeOf = Object.setPrototypeOf || function _setPrototypeOf(o, p) { o.__proto__ = p; return o; }; return _setPrototypeOf(o, p); }

import { addClass } from '../../../helpers/dom/element';
import { stopImmediatePropagation } from '../../../helpers/dom/event';
import { arrayEach, arrayFilter, arrayMap } from '../../../helpers/array';
import { isKey } from '../../../helpers/unicode';
import * as C from '../../../i18n/constants';
import { unifyColumnValues, intersectValues, toEmptyString } from '../utils';
import BaseComponent from './_base';
import MultipleSelectUI from '../ui/multipleSelect';
import { CONDITION_BY_VALUE, CONDITION_NONE } from '../constants';
import { getConditionDescriptor } from '../conditionRegisterer';
/**
 * @class ValueComponent
 * @plugin Filters
 */

var ValueComponent =
/*#__PURE__*/
function (_BaseComponent) {
  _inherits(ValueComponent, _BaseComponent);

  function ValueComponent(hotInstance, options) {
    var _this;

    _classCallCheck(this, ValueComponent);

    _this = _possibleConstructorReturn(this, _getPrototypeOf(ValueComponent).call(this, hotInstance));
    _this.id = options.id;
    _this.name = options.name;

    _this.elements.push(new MultipleSelectUI(_this.hot));

    _this.registerHooks();

    return _this;
  }
  /**
   * Register all necessary hooks.
   *
   * @private
   */


  _createClass(ValueComponent, [{
    key: "registerHooks",
    value: function registerHooks() {
      var _this2 = this;

      this.getMultipleSelectElement().addLocalHook('keydown', function (event) {
        return _this2.onInputKeyDown(event);
      });
    }
    /**
     * Set state of the component.
     *
     * @param {Object} value
     */

  }, {
    key: "setState",
    value: function setState(value) {
      this.reset();

      if (value && value.command.key === CONDITION_BY_VALUE) {
        var select = this.getMultipleSelectElement();
        select.setItems(value.itemsSnapshot);
        select.setValue(value.args[0]);
      }
    }
    /**
     * Export state of the component (get selected filter and filter arguments).
     *
     * @returns {Object} Returns object where `command` key keeps used condition filter and `args` key its arguments.
     */

  }, {
    key: "getState",
    value: function getState() {
      var select = this.getMultipleSelectElement();
      var availableItems = select.getItems();
      return {
        command: {
          key: select.isSelectedAllValues() || !availableItems.length ? CONDITION_NONE : CONDITION_BY_VALUE
        },
        args: [select.getValue()],
        itemsSnapshot: availableItems
      };
    }
    /**
     * Update state of component.
     *
     * @param {Object} stateInfo Information about state containing stack of edited column,
     * stack of dependent conditions, data factory and optional condition arguments change. It's described by object containing keys:
     * `editedConditionStack`, `dependentConditionStacks`, `visibleDataFactory` and `conditionArgsChange`.
     */

  }, {
    key: "updateState",
    value: function updateState(stateInfo) {
      var _this3 = this;

      var updateColumnState = function updateColumnState(column, conditions, conditionArgsChange, filteredRowsFactory, conditionsStack) {
        var _arrayFilter = arrayFilter(conditions, function (condition) {
          return condition.name === CONDITION_BY_VALUE;
        }),
            _arrayFilter2 = _slicedToArray(_arrayFilter, 1),
            firstByValueCondition = _arrayFilter2[0];

        var state = {};

        var defaultBlankCellValue = _this3.hot.getTranslatedPhrase(C.FILTERS_VALUES_BLANK_CELLS);

        if (firstByValueCondition) {
          var rowValues = arrayMap(filteredRowsFactory(column, conditionsStack), function (row) {
            return row.value;
          });
          rowValues = unifyColumnValues(rowValues);

          if (conditionArgsChange) {
            firstByValueCondition.args[0] = conditionArgsChange;
          }

          var selectedValues = [];
          var itemsSnapshot = intersectValues(rowValues, firstByValueCondition.args[0], defaultBlankCellValue, function (item) {
            if (item.checked) {
              selectedValues.push(item.value);
            }
          });
          state.args = [selectedValues];
          state.command = getConditionDescriptor(CONDITION_BY_VALUE);
          state.itemsSnapshot = itemsSnapshot;
        } else {
          state.args = [];
          state.command = getConditionDescriptor(CONDITION_NONE);
        }

        _this3.setCachedState(column, state);
      };

      updateColumnState(stateInfo.editedConditionStack.column, stateInfo.editedConditionStack.conditions, stateInfo.conditionArgsChange, stateInfo.filteredRowsFactory); // Shallow deep update of component state

      if (stateInfo.dependentConditionStacks.length) {
        updateColumnState(stateInfo.dependentConditionStacks[0].column, stateInfo.dependentConditionStacks[0].conditions, stateInfo.conditionArgsChange, stateInfo.filteredRowsFactory, stateInfo.editedConditionStack);
      }
    }
    /**
     * Get multiple select element.
     *
     * @returns {MultipleSelectUI}
     */

  }, {
    key: "getMultipleSelectElement",
    value: function getMultipleSelectElement() {
      return this.elements.filter(function (element) {
        return element instanceof MultipleSelectUI;
      })[0];
    }
    /**
     * Get object descriptor for menu item entry.
     *
     * @returns {Object}
     */

  }, {
    key: "getMenuItemDescriptor",
    value: function getMenuItemDescriptor() {
      var _this4 = this;

      return {
        key: this.id,
        name: this.name,
        isCommand: false,
        disableSelection: true,
        hidden: function hidden() {
          return _this4.isHidden();
        },
        renderer: function renderer(hot, wrapper, row, col, prop, value) {
          addClass(wrapper.parentNode, 'htFiltersMenuValue');

          var label = _this4.hot.rootDocument.createElement('div');

          addClass(label, 'htFiltersMenuLabel');
          label.textContent = value;
          wrapper.appendChild(label);

          if (!wrapper.parentNode.hasAttribute('ghost-table')) {
            arrayEach(_this4.elements, function (ui) {
              return wrapper.appendChild(ui.element);
            });
          }

          return wrapper;
        }
      };
    }
    /**
     * Reset elements to their initial state.
     */

  }, {
    key: "reset",
    value: function reset() {
      var defaultBlankCellValue = this.hot.getTranslatedPhrase(C.FILTERS_VALUES_BLANK_CELLS);
      var values = unifyColumnValues(this._getColumnVisibleValues());
      var items = intersectValues(values, values, defaultBlankCellValue);
      this.getMultipleSelectElement().setItems(items);

      _get(_getPrototypeOf(ValueComponent.prototype), "reset", this).call(this);

      this.getMultipleSelectElement().setValue(values);
    }
    /**
     * Key down listener.
     *
     * @private
     * @param {Event} event DOM event object.
     */

  }, {
    key: "onInputKeyDown",
    value: function onInputKeyDown(event) {
      if (isKey(event.keyCode, 'ESCAPE')) {
        this.runLocalHooks('cancel');
        stopImmediatePropagation(event);
      }
    }
    /**
     * Get data for currently selected column.
     *
     * @returns {Array}
     * @private
     */

  }, {
    key: "_getColumnVisibleValues",
    value: function _getColumnVisibleValues() {
      var lastSelectedColumn = this.hot.getPlugin('filters').getSelectedColumn();
      var visualIndex = lastSelectedColumn && lastSelectedColumn.visualIndex;
      return arrayMap(this.hot.getDataAtCol(visualIndex), function (v) {
        return toEmptyString(v);
      });
    }
  }]);

  return ValueComponent;
}(BaseComponent);

export default ValueComponent;