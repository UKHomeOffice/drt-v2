import "core-js/modules/es.error.cause.js";
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
import { Menu } from "../../../plugins/contextMenu/menu/index.mjs";
import { clone, extend } from "../../../helpers/object.mjs";
import { arrayEach } from "../../../helpers/array.mjs";
import { setAttribute } from "../../../helpers/dom/element.mjs";
import * as C from "../../../i18n/constants.mjs";
import { SEPARATOR } from "../../../plugins/contextMenu/predefinedItems/index.mjs";
import { BaseUI } from "./_base.mjs";
import { A11Y_HIDDEN, A11Y_LISTBOX } from "../../../helpers/a11y.mjs";
/**
 * @private
 * @class SelectUI
 */
var _menu = /*#__PURE__*/new WeakMap();
var _items = /*#__PURE__*/new WeakMap();
var _caption = /*#__PURE__*/new WeakMap();
var _captionElement = /*#__PURE__*/new WeakMap();
var _dropdown = /*#__PURE__*/new WeakMap();
var _SelectUI_brand = /*#__PURE__*/new WeakSet();
export class SelectUI extends BaseUI {
  static get DEFAULTS() {
    return clone({
      className: 'htUISelect',
      wrapIt: false,
      tabIndex: -1
    });
  }

  /**
   * Instance of {@link Menu}.
   *
   * @type {Menu}
   */

  constructor(hotInstance, options) {
    super(hotInstance, extend(SelectUI.DEFAULTS, options));
    /**
     * On menu selected listener.
     *
     * @param {object} command Selected item.
     */
    _classPrivateMethodInitSpec(this, _SelectUI_brand);
    _classPrivateFieldInitSpec(this, _menu, null);
    /**
     * List of available select options.
     *
     * @type {Array}
     */
    _classPrivateFieldInitSpec(this, _items, []);
    /**
     * The reference to the BaseUI instance of the caption.
     *
     * @type {BaseUI}
     */
    _classPrivateFieldInitSpec(this, _caption, void 0);
    /**
     * The reference to the table caption element.
     *
     * @type {HTMLTableCaptionElement}
     */
    _classPrivateFieldInitSpec(this, _captionElement, void 0);
    /**
     * The reference to the BaseUI instance of the dropdown.
     *
     * @type {BaseUI}
     */
    _classPrivateFieldInitSpec(this, _dropdown, void 0);
    this.registerHooks();
  }

  /**
   * Gets the instance of the Menu.
   *
   * @returns {Menu}
   */
  getMenu() {
    return _classPrivateFieldGet(_menu, this);
  }

  /**
   * Register all necessary hooks.
   */
  registerHooks() {
    this.addLocalHook('click', () => _assertClassBrand(_SelectUI_brand, this, _onClick).call(this));
  }

  /**
   * Set options which can be selected in the list.
   *
   * @param {Array} items Array of objects with required keys `key` and `name`.
   */
  setItems(items) {
    _classPrivateFieldSet(_items, this, this.translateNames(items));
    if (_classPrivateFieldGet(_menu, this)) {
      _classPrivateFieldGet(_menu, this).setMenuItems(_classPrivateFieldGet(_items, this));
    }
  }

  /**
   * Translate names of menu items.
   *
   * @param {Array} items Array of objects with required keys `key` and `name`.
   * @returns {Array} Items with translated `name` keys.
   */
  translateNames(items) {
    arrayEach(items, item => {
      item.name = this.translateIfPossible(item.name);
    });
    return items;
  }

  /**
   * Build DOM structure.
   */
  build() {
    super.build();
    _classPrivateFieldSet(_menu, this, new Menu(this.hot, {
      className: 'htSelectUI htFiltersConditionsMenu',
      keepInViewport: false,
      standalone: true,
      container: this.options.menuContainer
    }));
    _classPrivateFieldGet(_menu, this).setMenuItems(_classPrivateFieldGet(_items, this));
    const caption = new BaseUI(this.hot, {
      className: 'htUISelectCaption'
    });
    const dropdown = new BaseUI(this.hot, {
      className: 'htUISelectDropdown'
    });
    _classPrivateFieldSet(_caption, this, caption);
    _classPrivateFieldSet(_captionElement, this, caption.element);
    _classPrivateFieldSet(_dropdown, this, dropdown);
    if (this.hot.getSettings().ariaTags) {
      setAttribute(dropdown.element, [A11Y_HIDDEN()]);
      setAttribute(this._element, [A11Y_LISTBOX()]);
    }
    arrayEach([caption, dropdown], element => this._element.appendChild(element.element));
    _classPrivateFieldGet(_menu, this).addLocalHook('select', command => _assertClassBrand(_SelectUI_brand, this, _onMenuSelect).call(this, command));
    _classPrivateFieldGet(_menu, this).addLocalHook('afterClose', () => _assertClassBrand(_SelectUI_brand, this, _onMenuClosed).call(this));
    this.update();
  }

  /**
   * Update DOM structure.
   */
  update() {
    if (!this.isBuilt()) {
      return;
    }
    let conditionName;
    if (this.options.value) {
      conditionName = this.options.value.name;
    } else {
      conditionName = _classPrivateFieldGet(_menu, this).hot.getTranslatedPhrase(C.FILTERS_CONDITIONS_NONE);
    }
    _classPrivateFieldGet(_captionElement, this).textContent = conditionName;
    super.update();
  }

  /**
   * Open select dropdown menu with available options.
   */
  openOptions() {
    const rect = this.element.getBoundingClientRect();
    if (_classPrivateFieldGet(_menu, this)) {
      _classPrivateFieldGet(_menu, this).open();
      _classPrivateFieldGet(_menu, this).setPosition({
        left: this.hot.isLtr() ? rect.left - 5 : rect.left - 31,
        top: rect.top - 1,
        width: rect.width,
        height: rect.height
      });
      _classPrivateFieldGet(_menu, this).getNavigator().toFirstItem();
      _classPrivateFieldGet(_menu, this).getKeyboardShortcutsCtrl().addCustomShortcuts([{
        keys: [['Tab'], ['Shift', 'Tab']],
        callback: event => {
          this.closeOptions();
          this.runLocalHooks('tabKeydown', event);
        }
      }, {
        keys: [['Control/Meta', 'A']],
        callback: () => false
      }]);
    }
  }

  /**
   * Close select dropdown menu.
   */
  closeOptions() {
    if (_classPrivateFieldGet(_menu, this)) {
      _classPrivateFieldGet(_menu, this).close();
    }
  }

  /**
   * Focus element.
   */
  focus() {
    if (this.isBuilt()) {
      this.element.focus();
    }
  }
  /**
   * Destroy instance.
   */
  destroy() {
    if (_classPrivateFieldGet(_menu, this)) {
      _classPrivateFieldGet(_menu, this).destroy();
      _classPrivateFieldSet(_menu, this, null);
    }
    if (_classPrivateFieldGet(_caption, this)) {
      _classPrivateFieldGet(_caption, this).destroy();
    }
    if (_classPrivateFieldGet(_dropdown, this)) {
      _classPrivateFieldGet(_dropdown, this).destroy();
    }
    super.destroy();
  }
}
function _onMenuSelect(command) {
  if (command.name !== SEPARATOR) {
    this.options.value = command;
    this.update();
    this.runLocalHooks('select', this.options.value);
  }
}
/**
 * On menu closed listener.
 */
function _onMenuClosed() {
  this.runLocalHooks('afterClose');
}
/**
 * On element click listener.
 *
 * @private
 */
function _onClick() {
  this.openOptions();
}