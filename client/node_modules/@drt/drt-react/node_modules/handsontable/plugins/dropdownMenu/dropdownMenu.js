"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
var _base = require("../base");
var _array = require("../../helpers/array");
var _object = require("../../helpers/object");
var _commandExecutor = require("../contextMenu/commandExecutor");
var _utils = require("../contextMenu/utils");
var _element = require("../../helpers/dom/element");
var _itemsFactory = require("../contextMenu/itemsFactory");
var _menu = require("../contextMenu/menu");
var _hooks = require("../../core/hooks");
var _predefinedItems = require("../contextMenu/predefinedItems");
var _a11y = require("../../helpers/a11y");
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
_hooks.Hooks.getSingleton().register('afterDropdownMenuDefaultOptions');
_hooks.Hooks.getSingleton().register('beforeDropdownMenuShow');
_hooks.Hooks.getSingleton().register('afterDropdownMenuShow');
_hooks.Hooks.getSingleton().register('afterDropdownMenuHide');
_hooks.Hooks.getSingleton().register('afterDropdownMenuExecute');
const PLUGIN_KEY = exports.PLUGIN_KEY = 'dropdownMenu';
const PLUGIN_PRIORITY = exports.PLUGIN_PRIORITY = 230;
const BUTTON_CLASS_NAME = 'changeType';
const SHORTCUTS_GROUP = PLUGIN_KEY;

/* eslint-disable jsdoc/require-description-complete-sentence */
/**
 * @plugin DropdownMenu
 * @class DropdownMenu
 *
 * @description
 * This plugin creates the Handsontable Dropdown Menu. It allows to create a new column at any place in the grid
 * among [other features](@/guides/accessories-and-menus/context-menu/context-menu.md#context-menu-with-specific-options).
 * Possible values:
 * * `true` (to enable default options),
 * * `false` (to disable completely).
 *
 * or array of any available strings:
 * * `["col_left", "col_right", "remove_col", "---------", "undo", "redo"]`.
 *
 * See [the dropdown menu demo](@/guides/columns/column-menu/column-menu.md) for examples.
 *
 * @example
 * ::: only-for javascript
 * ```js
 * const container = document.getElementById('example');
 * const hot = new Handsontable(container, {
 *   data: data,
 *   colHeaders: true,
 *   // enable dropdown menu
 *   dropdownMenu: true
 * });
 *
 * // or
 * const hot = new Handsontable(container, {
 *   data: data,
 *   colHeaders: true,
 *   // enable and configure dropdown menu
 *   dropdownMenu: ['remove_col', '---------', 'make_read_only', 'alignment']
 * });
 * ```
 * :::
 *
 * ::: only-for react
 * ```jsx
 * <HotTable
 *   data={data}
 *   comments={true}
 *   // enable and configure dropdown menu
 *   dropdownMenu={['remove_col', '---------', 'make_read_only', 'alignment']}
 * />
 * ```
 * :::
 */
var _isButtonClicked = /*#__PURE__*/new WeakMap();
var _DropdownMenu_brand = /*#__PURE__*/new WeakSet();
class DropdownMenu extends _base.BasePlugin {
  static get PLUGIN_KEY() {
    return PLUGIN_KEY;
  }
  static get PLUGIN_PRIORITY() {
    return PLUGIN_PRIORITY;
  }
  static get PLUGIN_DEPS() {
    return ['plugin:AutoColumnSize'];
  }

  /**
   * Default menu items order when `dropdownMenu` is enabled by setting the config item to `true`.
   *
   * @returns {Array}
   */
  static get DEFAULT_ITEMS() {
    return [_predefinedItems.COLUMN_LEFT, _predefinedItems.COLUMN_RIGHT, _predefinedItems.SEPARATOR, _predefinedItems.REMOVE_COLUMN, _predefinedItems.SEPARATOR, _predefinedItems.CLEAR_COLUMN, _predefinedItems.SEPARATOR, _predefinedItems.READ_ONLY, _predefinedItems.SEPARATOR, _predefinedItems.ALIGNMENT];
  }

  /**
   * Instance of {@link CommandExecutor}.
   *
   * @private
   * @type {CommandExecutor}
   */

  constructor(hotInstance) {
    super(hotInstance);

    // One listener for enable/disable functionality
    /**
     * Add custom shortcuts to the provided menu instance.
     *
     * @param {Menu} menuInstance The menu instance.
     */
    _classPrivateMethodInitSpec(this, _DropdownMenu_brand);
    _defineProperty(this, "commandExecutor", new _commandExecutor.CommandExecutor(this.hot));
    /**
     * Instance of {@link ItemsFactory}.
     *
     * @private
     * @type {ItemsFactory}
     */
    _defineProperty(this, "itemsFactory", null);
    /**
     * Instance of {@link Menu}.
     *
     * @private
     * @type {Menu}
     */
    _defineProperty(this, "menu", null);
    /**
     * Flag which determines if the button that opens the menu was clicked.
     *
     * @type {boolean}
     */
    _classPrivateFieldInitSpec(this, _isButtonClicked, false);
    this.hot.addHook('afterGetColHeader', (col, TH) => _assertClassBrand(_DropdownMenu_brand, this, _onAfterGetColHeader).call(this, col, TH));
  }

  /**
   * Checks if the plugin is enabled in the handsontable settings. This method is executed in {@link Hooks#beforeInit}
   * hook and if it returns `true` then the {@link DropdownMenu#enablePlugin} method is called.
   *
   * @returns {boolean}
   */
  isEnabled() {
    return this.hot.getSettings()[PLUGIN_KEY];
  }

  /**
   * Enables the plugin functionality for this Handsontable instance.
   *
   * @fires Hooks#afterDropdownMenuDefaultOptions
   * @fires Hooks#beforeDropdownMenuSetItems
   */
  enablePlugin() {
    var _this = this;
    if (this.enabled) {
      return;
    }
    this.itemsFactory = new _itemsFactory.ItemsFactory(this.hot, DropdownMenu.DEFAULT_ITEMS);
    this.addHook('beforeOnCellMouseDown', function () {
      for (var _len = arguments.length, args = new Array(_len), _key = 0; _key < _len; _key++) {
        args[_key] = arguments[_key];
      }
      return _assertClassBrand(_DropdownMenu_brand, _this, _onBeforeOnCellMouseDown).call(_this, ...args);
    });
    this.addHook('beforeViewportScrollHorizontally', function () {
      for (var _len2 = arguments.length, args = new Array(_len2), _key2 = 0; _key2 < _len2; _key2++) {
        args[_key2] = arguments[_key2];
      }
      return _assertClassBrand(_DropdownMenu_brand, _this, _onBeforeViewportScrollHorizontally).call(_this, ...args);
    });
    const settings = this.hot.getSettings()[PLUGIN_KEY];
    const predefinedItems = {
      items: this.itemsFactory.getItems(settings)
    };
    this.registerEvents();
    if (typeof settings.callback === 'function') {
      this.commandExecutor.setCommonCallback(settings.callback);
    }
    this.registerShortcuts();
    super.enablePlugin();
    this.callOnPluginsReady(() => {
      this.hot.runHooks('afterDropdownMenuDefaultOptions', predefinedItems);
      this.itemsFactory.setPredefinedItems(predefinedItems.items);
      const menuItems = this.itemsFactory.getItems(settings);
      if (this.menu) {
        this.menu.destroy();
      }
      this.menu = new _menu.Menu(this.hot, {
        className: 'htDropdownMenu',
        keepInViewport: true,
        container: settings.uiContainer || this.hot.rootDocument.body
      });
      this.hot.runHooks('beforeDropdownMenuSetItems', menuItems);
      this.menu.setMenuItems(menuItems);
      this.menu.addLocalHook('beforeOpen', () => _assertClassBrand(_DropdownMenu_brand, this, _onMenuBeforeOpen).call(this));
      this.menu.addLocalHook('afterOpen', () => _assertClassBrand(_DropdownMenu_brand, this, _onMenuAfterOpen).call(this));
      this.menu.addLocalHook('afterSubmenuOpen', subMenuInstance => _assertClassBrand(_DropdownMenu_brand, this, _onSubMenuAfterOpen).call(this, subMenuInstance));
      this.menu.addLocalHook('afterClose', () => _assertClassBrand(_DropdownMenu_brand, this, _onMenuAfterClose).call(this));
      this.menu.addLocalHook('executeCommand', function () {
        for (var _len3 = arguments.length, params = new Array(_len3), _key3 = 0; _key3 < _len3; _key3++) {
          params[_key3] = arguments[_key3];
        }
        return _this.executeCommand.call(_this, ...params);
      });

      // Register all commands. Predefined and added by user or by plugins
      (0, _array.arrayEach)(menuItems, command => this.commandExecutor.registerCommand(command.key, command));
    });
  }

  /**
   * Updates the plugin's state.
   *
   * This method is executed when [`updateSettings()`](@/api/core.md#updatesettings) is invoked with any of the following configuration options:
   *  - [`dropdownMenu`](@/api/options.md#dropdownmenu)
   */
  updatePlugin() {
    this.disablePlugin();
    this.enablePlugin();
    super.updatePlugin();
  }

  /**
   * Disables the plugin functionality for this Handsontable instance.
   */
  disablePlugin() {
    this.close();
    if (this.menu) {
      this.menu.destroy();
    }
    this.unregisterShortcuts();
    super.disablePlugin();
  }

  /**
   * Register shortcuts responsible for toggling dropdown menu.
   *
   * @private
   */
  registerShortcuts() {
    const gridContext = this.hot.getShortcutManager().getContext('grid');
    const callback = () => {
      const {
        highlight
      } = this.hot.getSelectedRangeLast();
      if ((highlight.isHeader() && highlight.row === -1 || highlight.isCell()) && highlight.col >= 0) {
        this.hot.selectColumns(highlight.col, highlight.col, -1);
        const {
          from
        } = this.hot.getSelectedRangeLast();
        const offset = (0, _utils.getDocumentOffsetByElement)(this.menu.container, this.hot.rootDocument);
        const target = this.hot.getCell(-1, from.col, true).querySelector(`.${BUTTON_CLASS_NAME}`);
        const rect = target.getBoundingClientRect();
        this.open({
          left: rect.left + offset.left,
          top: rect.top + target.offsetHeight + offset.top
        }, {
          left: rect.width,
          right: 0,
          above: 0,
          below: 3
        });
        // Make sure the first item is selected (role=menuitem). Otherwise, screen readers
        // will block the Esc key for the whole menu.
        this.menu.getNavigator().toFirstItem();
      }
    };
    gridContext.addShortcuts([{
      keys: [['Shift', 'Alt', 'ArrowDown'], ['Control/Meta', 'Enter']],
      callback,
      runOnlyIf: () => {
        var _this$hot$getSelected;
        const highlight = (_this$hot$getSelected = this.hot.getSelectedRangeLast()) === null || _this$hot$getSelected === void 0 ? void 0 : _this$hot$getSelected.highlight;
        return highlight && this.hot.selection.isCellVisible(highlight) && highlight.isHeader() && !this.menu.isOpened();
      },
      captureCtrl: true,
      group: SHORTCUTS_GROUP
    }, {
      keys: [['Shift', 'Alt', 'ArrowDown']],
      callback,
      runOnlyIf: () => {
        var _this$hot$getSelected2;
        const highlight = (_this$hot$getSelected2 = this.hot.getSelectedRangeLast()) === null || _this$hot$getSelected2 === void 0 ? void 0 : _this$hot$getSelected2.highlight;
        return highlight && this.hot.selection.isCellVisible(highlight) && highlight.isCell() && !this.menu.isOpened();
      },
      group: SHORTCUTS_GROUP
    }]);
  }

  /**
   * Unregister shortcuts responsible for toggling dropdown menu.
   *
   * @private
   */
  unregisterShortcuts() {
    this.hot.getShortcutManager().getContext('grid').removeShortcutsByGroup(SHORTCUTS_GROUP);
  }

  /**
   * Registers the DOM listeners.
   *
   * @private
   */
  registerEvents() {
    this.eventManager.addEventListener(this.hot.rootElement, 'click', event => _assertClassBrand(_DropdownMenu_brand, this, _onTableClick).call(this, event));
  }

  /**
   * Opens menu and re-position it based on the passed coordinates.
   *
   * @param {{ top: number, left: number }|Event} position An object with `top` and `left` properties
   * which contains coordinates relative to the browsers viewport (without included scroll offsets).
   * Or if the native event is passed the menu will be positioned based on the `pageX` and `pageY`
   * coordinates.
   * @param {{ above: number, below: number, left: number, right: number }} offset An object allows applying
   * the offset to the menu position.
   * @fires Hooks#beforeDropdownMenuShow
   * @fires Hooks#afterDropdownMenuShow
   */
  open(position) {
    var _this$menu;
    let offset = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : {
      above: 0,
      below: 0,
      left: 0,
      right: 0
    };
    if ((_this$menu = this.menu) !== null && _this$menu !== void 0 && _this$menu.isOpened()) {
      return;
    }
    this.menu.open();
    (0, _object.objectEach)(offset, (value, key) => {
      this.menu.setOffset(key, value);
    });
    this.menu.setPosition(position);
  }

  /**
   * Closes dropdown menu.
   */
  close() {
    var _this$menu2;
    (_this$menu2 = this.menu) === null || _this$menu2 === void 0 || _this$menu2.close();
  }

  /**
   * Executes context menu command.
   *
   * The `executeCommand()` method works only for selected cells.
   *
   * When no cells are selected, `executeCommand()` doesn't do anything.
   *
   * You can execute all predefined commands:
   *  * `'col_left'` - Insert column left
   *  * `'col_right'` - Insert column right
   *  * `'clear_column'` - Clear selected column
   *  * `'remove_col'` - Remove column
   *  * `'undo'` - Undo last action
   *  * `'redo'` - Redo last action
   *  * `'make_read_only'` - Make cell read only
   *  * `'alignment:left'` - Alignment to the left
   *  * `'alignment:top'` - Alignment to the top
   *  * `'alignment:right'` - Alignment to the right
   *  * `'alignment:bottom'` - Alignment to the bottom
   *  * `'alignment:middle'` - Alignment to the middle
   *  * `'alignment:center'` - Alignment to the center (justify).
   *
   * Or you can execute command registered in settings where `key` is your command name.
   *
   * @param {string} commandName Command name to execute.
   * @param {*} params Additional parameters passed to the command executor.
   */
  executeCommand(commandName) {
    for (var _len4 = arguments.length, params = new Array(_len4 > 1 ? _len4 - 1 : 0), _key4 = 1; _key4 < _len4; _key4++) {
      params[_key4 - 1] = arguments[_key4];
    }
    this.commandExecutor.execute(commandName, ...params);
  }

  /**
   * Turns on / off listening on dropdown menu.
   *
   * @private
   * @param {boolean} listen Turn on listening when value is set to true, otherwise turn it off.
   */
  setListening() {
    let listen = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : true;
    if (this.menu.isOpened()) {
      if (listen) {
        this.menu.hotMenu.listen();
      } else {
        this.menu.hotMenu.unlisten();
      }
    }
  }
  /**
   * Destroys the plugin instance.
   */
  destroy() {
    this.close();
    if (this.menu) {
      this.menu.destroy();
    }
    super.destroy();
  }
}
exports.DropdownMenu = DropdownMenu;
function _addCustomShortcuts(menuInstance) {
  menuInstance.getKeyboardShortcutsCtrl().addCustomShortcuts([{
    keys: [['Control/Meta', 'A']],
    callback: () => false
  }]);
}
/**
 * Table click listener.
 *
 * @private
 * @param {Event} event The mouse event object.
 */
function _onTableClick(event) {
  if ((0, _element.hasClass)(event.target, BUTTON_CLASS_NAME)) {
    const offset = (0, _utils.getDocumentOffsetByElement)(this.menu.container, this.hot.rootDocument);
    const rect = event.target.getBoundingClientRect();
    event.stopPropagation();
    _classPrivateFieldSet(_isButtonClicked, this, false);
    this.open({
      left: rect.left + offset.left,
      top: rect.top + event.target.offsetHeight + offset.top
    }, {
      left: rect.width,
      right: 0,
      above: 0,
      below: 3
    });
  }
}
/**
 * On after get column header listener.
 *
 * @private
 * @param {number} col Visual column index.
 * @param {HTMLTableCellElement} TH Header's TH element.
 */
function _onAfterGetColHeader(col, TH) {
  // Corner or a higher-level header
  const headerRow = TH.parentNode;
  if (!headerRow) {
    return;
  }
  const headerRowList = headerRow.parentNode.childNodes;
  const level = Array.prototype.indexOf.call(headerRowList, headerRow);
  if (col < 0 || level !== headerRowList.length - 1) {
    return;
  }
  const existingButton = TH.querySelector(`.${BUTTON_CLASS_NAME}`);

  // Plugin enabled and buttons already exists, return.
  if (this.enabled && existingButton) {
    return;
  }
  // Plugin disabled and buttons still exists, so remove them.
  if (!this.enabled) {
    if (existingButton) {
      existingButton.parentNode.removeChild(existingButton);
    }
    return;
  }
  const button = this.hot.rootDocument.createElement('button');
  button.className = BUTTON_CLASS_NAME;
  button.type = 'button';
  button.tabIndex = -1;
  if (this.hot.getSettings().ariaTags) {
    (0, _element.setAttribute)(button, [(0, _a11y.A11Y_HIDDEN)(), (0, _a11y.A11Y_LABEL)(' ')]);
    (0, _element.setAttribute)(TH, [(0, _a11y.A11Y_HASPOPUP)('menu')]);
  }

  // prevent page reload on button click
  button.onclick = function () {
    return false;
  };
  TH.firstChild.insertBefore(button, TH.firstChild.firstChild);
}
/**
 * On menu before open listener.
 *
 * @private
 * @fires Hooks#beforeDropdownMenuShow
 */
function _onMenuBeforeOpen() {
  this.hot.runHooks('beforeDropdownMenuShow', this);
}
/**
 * On menu after open listener.
 *
 * @private
 * @fires Hooks#afterDropdownMenuShow
 */
function _onMenuAfterOpen() {
  this.hot.runHooks('afterDropdownMenuShow', this);
  _assertClassBrand(_DropdownMenu_brand, this, _addCustomShortcuts).call(this, this.menu);
}
/**
 * Listener for the `afterSubmenuOpen` hook.
 *
 * @private
 * @param {Menu} subMenuInstance The opened sub menu instance.
 */
function _onSubMenuAfterOpen(subMenuInstance) {
  _assertClassBrand(_DropdownMenu_brand, this, _addCustomShortcuts).call(this, subMenuInstance);
}
/**
 * On menu after close listener.
 *
 * @private
 * @fires Hooks#afterDropdownMenuHide
 */
function _onMenuAfterClose() {
  this.hot.listen();
  this.hot.runHooks('afterDropdownMenuHide', this);
}
/**
 * Hook allows blocking horizontal scroll when the menu is opened by clicking on
 * the column header button. This prevents from scrolling the viewport (jump effect) when
 * the button is clicked.
 *
 * @param {number} visualColumn Visual column index.
 * @returns {number | null}
 */
function _onBeforeViewportScrollHorizontally(visualColumn) {
  return _classPrivateFieldGet(_isButtonClicked, this) ? null : visualColumn;
}
/**
 * Hook sets the internal flag to `true` when the button is clicked.
 *
 * @param {MouseEvent} event The mouse event object.
 */
function _onBeforeOnCellMouseDown(event) {
  if ((0, _element.hasClass)(event.target, BUTTON_CLASS_NAME)) {
    _classPrivateFieldSet(_isButtonClicked, this, true);
  }
}
DropdownMenu.SEPARATOR = {
  name: _predefinedItems.SEPARATOR
};