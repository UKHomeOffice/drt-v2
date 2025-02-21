"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
require("core-js/modules/es.array.push.js");
require("core-js/modules/esnext.iterator.constructor.js");
require("core-js/modules/esnext.iterator.filter.js");
require("core-js/modules/esnext.iterator.for-each.js");
require("core-js/modules/esnext.iterator.map.js");
var _element = require("../../../helpers/dom/element");
var _object = require("../../../helpers/object");
var _unicode = require("../../../helpers/unicode");
var _function = require("../../../helpers/function");
var _data = require("../../../helpers/data");
var C = _interopRequireWildcard(require("../../../i18n/constants"));
var _event = require("../../../helpers/dom/event");
var _base = require("./_base");
var _input = require("./input");
var _link = require("./link");
var _utils = require("../utils");
function _getRequireWildcardCache(e) { if ("function" != typeof WeakMap) return null; var r = new WeakMap(), t = new WeakMap(); return (_getRequireWildcardCache = function (e) { return e ? t : r; })(e); }
function _interopRequireWildcard(e, r) { if (!r && e && e.__esModule) return e; if (null === e || "object" != typeof e && "function" != typeof e) return { default: e }; var t = _getRequireWildcardCache(r); if (t && t.has(e)) return t.get(e); var n = { __proto__: null }, a = Object.defineProperty && Object.getOwnPropertyDescriptor; for (var u in e) if ("default" !== u && {}.hasOwnProperty.call(e, u)) { var i = a ? Object.getOwnPropertyDescriptor(e, u) : null; i && (i.get || i.set) ? Object.defineProperty(n, u, i) : n[u] = e[u]; } return n.default = e, t && t.set(e, n), n; }
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
const SHORTCUTS_GROUP = 'multipleSelect.itemBox';

/**
 * @private
 * @class MultipleSelectUI
 */
var _items = /*#__PURE__*/new WeakMap();
var _itemsBox = /*#__PURE__*/new WeakMap();
var _locale = /*#__PURE__*/new WeakMap();
var _searchInput = /*#__PURE__*/new WeakMap();
var _selectAllUI = /*#__PURE__*/new WeakMap();
var _clearAllUI = /*#__PURE__*/new WeakMap();
var _MultipleSelectUI_brand = /*#__PURE__*/new WeakSet();
class MultipleSelectUI extends _base.BaseUI {
  static get DEFAULTS() {
    return (0, _object.clone)({
      className: 'htUIMultipleSelect',
      value: []
    });
  }

  /**
   * List of available select options.
   *
   * @type {Array}
   */

  constructor(hotInstance, options) {
    super(hotInstance, (0, _object.extend)(MultipleSelectUI.DEFAULTS, options));
    /**
     * 'input' event listener for input element.
     *
     * @param {Event} event DOM event.
     */
    _classPrivateMethodInitSpec(this, _MultipleSelectUI_brand);
    _classPrivateFieldInitSpec(this, _items, []);
    /**
     * Handsontable instance used as items list element.
     *
     * @type {Handsontable}
     */
    _classPrivateFieldInitSpec(this, _itemsBox, void 0);
    /**
     * A locale for the component used to compare filtered values.
     *
     * @type {string}
     */
    _classPrivateFieldInitSpec(this, _locale, void 0);
    /**
     * Input element.
     *
     * @type {InputUI}
     */
    _classPrivateFieldInitSpec(this, _searchInput, void 0);
    /**
     * "Select all" UI element.
     *
     * @type {LinkUI}
     */
    _classPrivateFieldInitSpec(this, _selectAllUI, void 0);
    /**
     * "Clear" UI element.
     *
     * @type {LinkUI}
     */
    _classPrivateFieldInitSpec(this, _clearAllUI, void 0);
    _classPrivateFieldSet(_searchInput, this, new _input.InputUI(this.hot, {
      placeholder: C.FILTERS_BUTTONS_PLACEHOLDER_SEARCH,
      className: 'htUIMultipleSelectSearch'
    }));
    _classPrivateFieldSet(_selectAllUI, this, new _link.LinkUI(this.hot, {
      textContent: C.FILTERS_BUTTONS_SELECT_ALL,
      className: 'htUISelectAll'
    }));
    _classPrivateFieldSet(_clearAllUI, this, new _link.LinkUI(this.hot, {
      textContent: C.FILTERS_BUTTONS_CLEAR,
      className: 'htUIClearAll'
    }));
    this.registerHooks();
  }

  /**
   * Gets the instance of the internal Handsontable that acts here as a listbox component.
   *
   * @returns {Handsontable}
   */
  getItemsBox() {
    return _classPrivateFieldGet(_itemsBox, this);
  }

  /**
   * Register all necessary hooks.
   */
  registerHooks() {
    _classPrivateFieldGet(_searchInput, this).addLocalHook('keydown', event => _assertClassBrand(_MultipleSelectUI_brand, this, _onInputKeyDown).call(this, event));
    _classPrivateFieldGet(_searchInput, this).addLocalHook('input', event => _assertClassBrand(_MultipleSelectUI_brand, this, _onInput).call(this, event));
    _classPrivateFieldGet(_selectAllUI, this).addLocalHook('click', event => _assertClassBrand(_MultipleSelectUI_brand, this, _onSelectAllClick).call(this, event));
    _classPrivateFieldGet(_clearAllUI, this).addLocalHook('click', event => _assertClassBrand(_MultipleSelectUI_brand, this, _onClearAllClick).call(this, event));
  }

  /**
   * Set available options.
   *
   * @param {Array} items Array of objects with `checked` and `label` property.
   */
  setItems(items) {
    var _classPrivateFieldGet2;
    _classPrivateFieldSet(_items, this, items);
    (_classPrivateFieldGet2 = _classPrivateFieldGet(_itemsBox, this)) === null || _classPrivateFieldGet2 === void 0 || _classPrivateFieldGet2.loadData(_classPrivateFieldGet(_items, this));
  }

  /**
   * Set a locale for the component.
   *
   * @param {string} locale Locale used for filter actions performed on data, ie. `en-US`.
   */
  setLocale(locale) {
    _classPrivateFieldSet(_locale, this, locale);
  }

  /**
   * Get a locale for the component.
   *
   * @returns {string}
   */
  getLocale() {
    return _classPrivateFieldGet(_locale, this);
  }

  /**
   * Get all available options.
   *
   * @returns {Array}
   */
  getItems() {
    return [..._classPrivateFieldGet(_items, this)];
  }

  /**
   * Get element value.
   *
   * @returns {Array} Array of selected values.
   */
  getValue() {
    return itemsToValue(_classPrivateFieldGet(_items, this));
  }

  /**
   * Gets the instance of the search input element.
   *
   * @returns {InputUI}
   */
  getSearchInputElement() {
    return _classPrivateFieldGet(_searchInput, this);
  }

  /**
   * Gets the instance of the "select all" link element.
   *
   * @returns {LinkUI}
   */
  getSelectAllElement() {
    return _classPrivateFieldGet(_selectAllUI, this);
  }

  /**
   * Gets the instance of the "clear" link element.
   *
   * @returns {LinkUI}
   */
  getClearAllElement() {
    return _classPrivateFieldGet(_clearAllUI, this);
  }

  /**
   * Check if all values listed in element are selected.
   *
   * @returns {boolean}
   */
  isSelectedAllValues() {
    return _classPrivateFieldGet(_items, this).length === this.getValue().length;
  }

  /**
   * Build DOM structure.
   */
  build() {
    super.build();
    const {
      rootDocument
    } = this.hot;
    const itemsBoxWrapper = rootDocument.createElement('div');
    const selectionControl = new _base.BaseUI(this.hot, {
      className: 'htUISelectionControls',
      children: [_classPrivateFieldGet(_selectAllUI, this), _classPrivateFieldGet(_clearAllUI, this)]
    });
    this._element.appendChild(_classPrivateFieldGet(_searchInput, this).element);
    this._element.appendChild(selectionControl.element);
    this._element.appendChild(itemsBoxWrapper);
    const hotInitializer = wrapper => {
      var _classPrivateFieldGet3;
      if (!this._element) {
        return;
      }
      (_classPrivateFieldGet3 = _classPrivateFieldGet(_itemsBox, this)) === null || _classPrivateFieldGet3 === void 0 || _classPrivateFieldGet3.destroy();
      (0, _element.addClass)(wrapper, 'htUIMultipleSelectHot');

      // Constructs and initializes a new Handsontable instance
      _classPrivateFieldSet(_itemsBox, this, new this.hot.constructor(wrapper, {
        data: _classPrivateFieldGet(_items, this),
        columns: [{
          data: 'checked',
          type: 'checkbox',
          label: {
            property: 'visualValue',
            position: 'after'
          }
        }],
        beforeRenderer: (TD, row, col, prop, value, cellProperties) => {
          TD.title = cellProperties.instance.getDataAtRowProp(row, cellProperties.label.property);
        },
        afterListen: () => {
          this.runLocalHooks('focus', this);
        },
        beforeOnCellMouseUp: () => {
          _classPrivateFieldGet(_itemsBox, this).listen();
        },
        colWidths: () => _classPrivateFieldGet(_itemsBox, this).container.scrollWidth - (0, _element.getScrollbarWidth)(rootDocument),
        maxCols: 1,
        autoWrapCol: true,
        height: 110,
        copyPaste: false,
        disableVisualSelection: 'area',
        fillHandle: false,
        fragmentSelection: 'cell',
        tabMoves: {
          row: 1,
          col: 0
        },
        themeName: this.hot.getCurrentThemeName(),
        layoutDirection: this.hot.isRtl() ? 'rtl' : 'ltr'
      }));
      _classPrivateFieldGet(_itemsBox, this).init();
      this.hot.addHook('afterSetTheme', (themeName, firstRun) => {
        if (!firstRun) {
          _classPrivateFieldGet(_itemsBox, this).useTheme(themeName);
        }
      });
      const shortcutManager = _classPrivateFieldGet(_itemsBox, this).getShortcutManager();
      const gridContext = shortcutManager.getContext('grid');
      gridContext.removeShortcutsByKeys(['Tab']);
      gridContext.removeShortcutsByKeys(['Shift', 'Tab']);
      gridContext.addShortcut({
        keys: [['Escape']],
        callback: event => {
          this.runLocalHooks('keydown', event, this);
        },
        group: SHORTCUTS_GROUP
      });
      gridContext.addShortcut({
        keys: [['Tab'], ['Shift', 'Tab']],
        callback: event => {
          _classPrivateFieldGet(_itemsBox, this).deselectCell();
          this.runLocalHooks('keydown', event, this);
          this.runLocalHooks('listTabKeydown', event, this);
        },
        group: SHORTCUTS_GROUP
      });
    };
    hotInitializer(itemsBoxWrapper);
    this.hot._registerTimeout(() => hotInitializer(itemsBoxWrapper), 100);
  }

  /**
   * Focus element.
   */
  focus() {
    if (this.isBuilt()) {
      _classPrivateFieldGet(_itemsBox, this).listen();
    }
  }

  /**
   * Reset DOM structure.
   */
  reset() {
    _classPrivateFieldGet(_searchInput, this).reset();
    _classPrivateFieldGet(_selectAllUI, this).reset();
    _classPrivateFieldGet(_clearAllUI, this).reset();
  }

  /**
   * Update DOM structure.
   */
  update() {
    if (!this.isBuilt()) {
      return;
    }
    _classPrivateFieldGet(_itemsBox, this).loadData(valueToItems(_classPrivateFieldGet(_items, this), this.options.value));
    super.update();
  }

  /**
   * Destroy instance.
   */
  destroy() {
    var _classPrivateFieldGet4;
    (_classPrivateFieldGet4 = _classPrivateFieldGet(_itemsBox, this)) === null || _classPrivateFieldGet4 === void 0 || _classPrivateFieldGet4.destroy();
    _classPrivateFieldGet(_searchInput, this).destroy();
    _classPrivateFieldGet(_clearAllUI, this).destroy();
    _classPrivateFieldGet(_selectAllUI, this).destroy();
    _classPrivateFieldSet(_searchInput, this, null);
    _classPrivateFieldSet(_clearAllUI, this, null);
    _classPrivateFieldSet(_selectAllUI, this, null);
    _classPrivateFieldSet(_itemsBox, this, null);
    _classPrivateFieldSet(_items, this, null);
    super.destroy();
  }
}
exports.MultipleSelectUI = MultipleSelectUI;
function _onInput(event) {
  const value = event.target.value.toLocaleLowerCase(this.getLocale());
  let filteredItems;
  if (value === '') {
    filteredItems = [..._classPrivateFieldGet(_items, this)];
  } else {
    filteredItems = _classPrivateFieldGet(_items, this).filter(item => `${item.value}`.toLocaleLowerCase(this.getLocale()).indexOf(value) >= 0);
  }
  _classPrivateFieldGet(_itemsBox, this).loadData(filteredItems);
}
/**
 * 'keydown' event listener for input element.
 *
 * @param {Event} event DOM event.
 */
function _onInputKeyDown(event) {
  this.runLocalHooks('keydown', event, this);
  const isKeyCode = (0, _function.partial)(_unicode.isKey, event.keyCode);
  if (isKeyCode('ARROW_DOWN')) {
    event.preventDefault();
    (0, _event.stopImmediatePropagation)(event);
    _classPrivateFieldGet(_itemsBox, this).listen();
    _classPrivateFieldGet(_itemsBox, this).selectCell(0, 0);
  }
}
/**
 * On click listener for "Select all" link.
 *
 * @param {DOMEvent} event The mouse event object.
 */
function _onSelectAllClick(event) {
  const changes = [];
  event.preventDefault();
  _classPrivateFieldGet(_itemsBox, this).getSourceData().forEach((row, rowIndex) => {
    row.checked = true;
    changes.push((0, _data.dataRowToChangesArray)(row, rowIndex)[0]);
  });
  _classPrivateFieldGet(_itemsBox, this).setSourceDataAtCell(changes);
}
/**
 * On click listener for "Clear" link.
 *
 * @param {DOMEvent} event The mouse event object.
 */
function _onClearAllClick(event) {
  const changes = [];
  event.preventDefault();
  _classPrivateFieldGet(_itemsBox, this).getSourceData().forEach((row, rowIndex) => {
    row.checked = false;
    changes.push((0, _data.dataRowToChangesArray)(row, rowIndex)[0]);
  });
  _classPrivateFieldGet(_itemsBox, this).setSourceDataAtCell(changes);
}
var _default = exports.default = MultipleSelectUI;
/**
 * Pick up object items based on selected values.
 *
 * @param {Array} availableItems Base collection to compare values.
 * @param {Array} selectedValue Flat array with selected values.
 * @returns {Array}
 */
function valueToItems(availableItems, selectedValue) {
  const arrayAssertion = (0, _utils.createArrayAssertion)(selectedValue);
  return availableItems.map(item => {
    item.checked = arrayAssertion(item.value);
    return item;
  });
}

/**
 * Convert all checked items into flat array.
 *
 * @param {Array} availableItems Base collection.
 * @returns {Array}
 */
function itemsToValue(availableItems) {
  const items = [];
  availableItems.forEach(item => {
    if (item.checked) {
      items.push(item.value);
    }
  });
  return items;
}