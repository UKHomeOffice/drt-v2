import "core-js/modules/es.error.cause.js";
import "core-js/modules/es.array.push.js";
import "core-js/modules/esnext.iterator.constructor.js";
import "core-js/modules/esnext.iterator.filter.js";
import "core-js/modules/esnext.iterator.map.js";
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
import { addClass } from "../../../helpers/dom/element.mjs";
import { stopImmediatePropagation } from "../../../helpers/dom/event.mjs";
import { arrayEach, arrayFilter, arrayMap } from "../../../helpers/array.mjs";
import { isKey } from "../../../helpers/unicode.mjs";
import * as C from "../../../i18n/constants.mjs";
import { unifyColumnValues, intersectValues, toEmptyString } from "../utils.mjs";
import { BaseComponent } from "./_base.mjs";
import { MultipleSelectUI } from "../ui/multipleSelect.mjs";
import { CONDITION_BY_VALUE, CONDITION_NONE } from "../constants.mjs";
import { getConditionDescriptor } from "../conditionRegisterer.mjs";
import { getRenderedValue as getRenderedNumericValue } from "../../../renderers/numericRenderer/index.mjs";
/**
 * @private
 * @class ValueComponent
 */
var _ValueComponent_brand = /*#__PURE__*/new WeakSet();
export class ValueComponent extends BaseComponent {
  constructor(hotInstance, options) {
    super(hotInstance, {
      id: options.id,
      stateless: false
    });
    /**
     * Key down listener.
     *
     * @param {Event} event The DOM event object.
     */
    _classPrivateMethodInitSpec(this, _ValueComponent_brand);
    /**
     * The name of the component.
     *
     * @type {string}
     */
    _defineProperty(this, "name", '');
    this.name = options.name;
    this.elements.push(new MultipleSelectUI(this.hot));
    this.registerHooks();
  }

  /**
   * Register all necessary hooks.
   *
   * @private
   */
  registerHooks() {
    this.getMultipleSelectElement().addLocalHook('keydown', event => _assertClassBrand(_ValueComponent_brand, this, _onInputKeyDown).call(this, event)).addLocalHook('listTabKeydown', event => this.runLocalHooks('listTabKeydown', event));
    this.hot.addHook('modifyFiltersMultiSelectValue', (value, meta) => _assertClassBrand(_ValueComponent_brand, this, _onModifyDisplayedValue).call(this, value, meta));
  }

  /**
   * Gets the list of elements from which the component is built.
   *
   * @returns {BaseUI[]}
   */
  getElements() {
    const selectElement = this.getMultipleSelectElement();
    return [selectElement.getSearchInputElement(), selectElement.getSelectAllElement(), selectElement.getClearAllElement(), this.getMultipleSelectElement()];
  }

  /**
   * Set state of the component.
   *
   * @param {object} value The component value.
   */
  setState(value) {
    this.reset();
    if (value && value.command.key === CONDITION_BY_VALUE) {
      const select = this.getMultipleSelectElement();
      select.setItems(value.itemsSnapshot);
      select.setValue(value.args[0]);
      select.setLocale(value.locale);
    }
  }

  /**
   * Export state of the component (get selected filter and filter arguments).
   *
   * @returns {object} Returns object where `command` key keeps used condition filter and `args` key its arguments.
   */
  getState() {
    const select = this.getMultipleSelectElement();
    const availableItems = select.getItems();
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
   * @param {object} stateInfo Information about state containing stack of edited column,
   * stack of dependent conditions, data factory and optional condition arguments change. It's described by object containing keys:
   * `editedConditionStack`, `dependentConditionStacks`, `visibleDataFactory` and `conditionArgsChange`.
   */
  updateState(stateInfo) {
    const updateColumnState = (physicalColumn, conditions, conditionArgsChange, filteredRowsFactory, conditionsStack) => {
      const [firstByValueCondition] = arrayFilter(conditions, condition => condition.name === CONDITION_BY_VALUE);
      const state = {};
      const defaultBlankCellValue = this.hot.getTranslatedPhrase(C.FILTERS_VALUES_BLANK_CELLS);
      if (firstByValueCondition) {
        const filteredRows = filteredRowsFactory(physicalColumn, conditionsStack);
        const rowValues = arrayMap(filteredRows, row => row.value);
        const rowMetaMap = new Map(filteredRows.map(row => [row.value, this.hot.getCellMeta(row.meta.visualRow, row.meta.visualCol)]));
        const unifiedRowValues = unifyColumnValues(rowValues);
        if (conditionArgsChange) {
          firstByValueCondition.args[0] = conditionArgsChange;
        }
        const selectedValues = [];
        const itemsSnapshot = intersectValues(unifiedRowValues, firstByValueCondition.args[0], defaultBlankCellValue, item => {
          if (item.checked) {
            selectedValues.push(item.value);
          }
          _assertClassBrand(_ValueComponent_brand, this, _triggerModifyMultipleSelectionValueHook).call(this, item, rowMetaMap);
        });
        const column = stateInfo.editedConditionStack.column;
        state.locale = this.hot.getCellMeta(0, column).locale;
        state.args = [selectedValues];
        state.command = getConditionDescriptor(CONDITION_BY_VALUE);
        state.itemsSnapshot = itemsSnapshot;
      } else {
        state.args = [];
        state.command = getConditionDescriptor(CONDITION_NONE);
      }
      this.state.setValueAtIndex(physicalColumn, state);
    };
    updateColumnState(stateInfo.editedConditionStack.column, stateInfo.editedConditionStack.conditions, stateInfo.conditionArgsChange, stateInfo.filteredRowsFactory);

    // Update the next "by_value" component (filter column conditions added after this condition).
    // Its list of values has to be updated. As the new values by default are unchecked,
    // the further component update is unnecessary.
    if (stateInfo.dependentConditionStacks.length) {
      updateColumnState(stateInfo.dependentConditionStacks[0].column, stateInfo.dependentConditionStacks[0].conditions, stateInfo.conditionArgsChange, stateInfo.filteredRowsFactory, stateInfo.editedConditionStack);
    }
  }

  /**
   * Get multiple select element.
   *
   * @returns {MultipleSelectUI}
   */
  getMultipleSelectElement() {
    return this.elements.filter(element => element instanceof MultipleSelectUI)[0];
  }

  /**
   * Get object descriptor for menu item entry.
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
        addClass(wrapper.parentNode, 'htFiltersMenuValue');
        const label = this.hot.rootDocument.createElement('div');
        addClass(label, 'htFiltersMenuLabel');
        label.textContent = value;
        wrapper.appendChild(label);

        // The MultipleSelectUI should not extend the menu width (it should adjust to the menu item width only).
        // That's why it's skipped from rendering when the GhostTable tries to render it.
        if (!wrapper.parentElement.hasAttribute('ghost-table')) {
          arrayEach(this.elements, ui => wrapper.appendChild(ui.element));
        }
        return wrapper;
      }
    };
  }

  /**
   * Reset elements to their initial state.
   */
  reset() {
    const defaultBlankCellValue = this.hot.getTranslatedPhrase(C.FILTERS_VALUES_BLANK_CELLS);
    const rowEntries = this._getColumnVisibleValues();
    const rowValues = rowEntries.map(entry => entry.value);
    const rowMetaMap = new Map(rowEntries.map(row => [row.value, row.meta]));
    const values = unifyColumnValues(rowValues);
    const items = intersectValues(values, values, defaultBlankCellValue, item => {
      _assertClassBrand(_ValueComponent_brand, this, _triggerModifyMultipleSelectionValueHook).call(this, item, rowMetaMap);
    });
    this.getMultipleSelectElement().setItems(items);
    super.reset();
    this.getMultipleSelectElement().setValue(values);
    const selectedColumn = this.hot.getPlugin('filters').getSelectedColumn();
    if (selectedColumn !== null) {
      this.getMultipleSelectElement().setLocale(this.hot.getCellMeta(0, selectedColumn.visualIndex).locale);
    }
  }
  /**
   * Get data for currently selected column.
   *
   * @returns {Array}
   * @private
   */
  _getColumnVisibleValues() {
    const selectedColumn = this.hot.getPlugin('filters').getSelectedColumn();
    if (selectedColumn === null) {
      return [];
    }
    return arrayMap(this.hot.getDataAtCol(selectedColumn.visualIndex), (v, rowIndex) => {
      return {
        value: toEmptyString(v),
        meta: this.hot.getCellMeta(rowIndex, selectedColumn.visualIndex)
      };
    });
  }
}
function _onInputKeyDown(event) {
  if (isKey(event.keyCode, 'ESCAPE')) {
    this.runLocalHooks('cancel');
    stopImmediatePropagation(event);
  }
}
/**
 * Trigger the `modifyFiltersMultiSelectValue` hook.
 *
 * @param {object} item Item from the multiple select list.
 * @param {Map} metaMap Map of row meta objects.
 */
function _triggerModifyMultipleSelectionValueHook(item, metaMap) {
  if (this.hot.hasHook('modifyFiltersMultiSelectValue')) {
    item.visualValue = this.hot.runHooks('modifyFiltersMultiSelectValue', item.visualValue, metaMap.get(item.value));
  }
}
/**
 * Modify the value displayed in the multiple select list.
 *
 * @param {*} value Cell value.
 * @param {object} meta The cell meta object.
 * @returns {*} Returns the modified value.
 */
function _onModifyDisplayedValue(value, meta) {
  switch (meta.type) {
    case 'numeric':
      return getRenderedNumericValue(value, meta);
    default:
      return value;
  }
}