import "core-js/modules/es.symbol";
import "core-js/modules/es.symbol.description";
import "core-js/modules/es.symbol.iterator";
import "core-js/modules/es.array.iterator";
import "core-js/modules/es.function.name";
import "core-js/modules/es.object.get-own-property-descriptor";
import "core-js/modules/es.object.get-prototype-of";
import "core-js/modules/es.object.set-prototype-of";
import "core-js/modules/es.object.to-string";
import "core-js/modules/es.reflect.get";
import "core-js/modules/es.string.iterator";
import "core-js/modules/es.weak-map";
import "core-js/modules/web.dom-collections.iterator";

function _typeof(obj) { if (typeof Symbol === "function" && typeof Symbol.iterator === "symbol") { _typeof = function _typeof(obj) { return typeof obj; }; } else { _typeof = function _typeof(obj) { return obj && typeof Symbol === "function" && obj.constructor === Symbol && obj !== Symbol.prototype ? "symbol" : typeof obj; }; } return _typeof(obj); }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _possibleConstructorReturn(self, call) { if (call && (_typeof(call) === "object" || typeof call === "function")) { return call; } return _assertThisInitialized(self); }

function _assertThisInitialized(self) { if (self === void 0) { throw new ReferenceError("this hasn't been initialised - super() hasn't been called"); } return self; }

function _get(target, property, receiver) { if (typeof Reflect !== "undefined" && Reflect.get) { _get = Reflect.get; } else { _get = function _get(target, property, receiver) { var base = _superPropBase(target, property); if (!base) return; var desc = Object.getOwnPropertyDescriptor(base, property); if (desc.get) { return desc.get.call(receiver); } return desc.value; }; } return _get(target, property, receiver || target); }

function _superPropBase(object, property) { while (!Object.prototype.hasOwnProperty.call(object, property)) { object = _getPrototypeOf(object); if (object === null) break; } return object; }

function _getPrototypeOf(o) { _getPrototypeOf = Object.setPrototypeOf ? Object.getPrototypeOf : function _getPrototypeOf(o) { return o.__proto__ || Object.getPrototypeOf(o); }; return _getPrototypeOf(o); }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

function _inherits(subClass, superClass) { if (typeof superClass !== "function" && superClass !== null) { throw new TypeError("Super expression must either be null or a function"); } subClass.prototype = Object.create(superClass && superClass.prototype, { constructor: { value: subClass, writable: true, configurable: true } }); if (superClass) _setPrototypeOf(subClass, superClass); }

function _setPrototypeOf(o, p) { _setPrototypeOf = Object.setPrototypeOf || function _setPrototypeOf(o, p) { o.__proto__ = p; return o; }; return _setPrototypeOf(o, p); }

import Menu from '../../../plugins/contextMenu/menu';
import { clone, extend } from '../../../helpers/object';
import { arrayEach } from '../../../helpers/array';
import * as C from '../../../i18n/constants';
import { SEPARATOR } from '../../../plugins/contextMenu/predefinedItems';
import BaseUI from './_base';
var privatePool = new WeakMap();
/**
 * @class SelectUI
 * @util
 */

var SelectUI =
/*#__PURE__*/
function (_BaseUI) {
  _inherits(SelectUI, _BaseUI);

  _createClass(SelectUI, null, [{
    key: "DEFAULTS",
    get: function get() {
      return clone({
        className: 'htUISelect',
        wrapIt: false
      });
    }
  }]);

  function SelectUI(hotInstance, options) {
    var _this;

    _classCallCheck(this, SelectUI);

    _this = _possibleConstructorReturn(this, _getPrototypeOf(SelectUI).call(this, hotInstance, extend(SelectUI.DEFAULTS, options)));
    privatePool.set(_assertThisInitialized(_this), {});
    /**
     * Instance of {@link Menu}.
     *
     * @type {Menu}
     */

    _this.menu = null;
    /**
     * List of available select options.
     *
     * @type {Array}
     */

    _this.items = [];

    _this.registerHooks();

    return _this;
  }
  /**
   * Register all necessary hooks.
   */


  _createClass(SelectUI, [{
    key: "registerHooks",
    value: function registerHooks() {
      var _this2 = this;

      this.addLocalHook('click', function () {
        return _this2.onClick();
      });
    }
    /**
     * Set options which can be selected in the list.
     *
     * @param {Array} items Array of objects with required keys `key` and `name`.
     */

  }, {
    key: "setItems",
    value: function setItems(items) {
      this.items = this.translateNames(items);

      if (this.menu) {
        this.menu.setMenuItems(this.items);
      }
    }
    /**
     * Translate names of menu items.
     *
     * @param {Array} items Array of objects with required keys `key` and `name`.
     * @returns {Array} Items with translated `name` keys.
     */

  }, {
    key: "translateNames",
    value: function translateNames(items) {
      var _this3 = this;

      arrayEach(items, function (item) {
        item.name = _this3.translateIfPossible(item.name);
      });
      return items;
    }
    /**
     * Build DOM structure.
     */

  }, {
    key: "build",
    value: function build() {
      var _this4 = this;

      _get(_getPrototypeOf(SelectUI.prototype), "build", this).call(this);

      this.menu = new Menu(this.hot, {
        className: 'htSelectUI htFiltersConditionsMenu',
        keepInViewport: false,
        standalone: true
      });
      this.menu.setMenuItems(this.items);
      var caption = new BaseUI(this.hot, {
        className: 'htUISelectCaption'
      });
      var dropdown = new BaseUI(this.hot, {
        className: 'htUISelectDropdown'
      });
      var priv = privatePool.get(this);
      priv.caption = caption;
      priv.captionElement = caption.element;
      priv.dropdown = dropdown;
      arrayEach([caption, dropdown], function (element) {
        return _this4._element.appendChild(element.element);
      });
      this.menu.addLocalHook('select', function (command) {
        return _this4.onMenuSelect(command);
      });
      this.menu.addLocalHook('afterClose', function () {
        return _this4.onMenuClosed();
      });
      this.update();
    }
    /**
     * Update DOM structure.
     */

  }, {
    key: "update",
    value: function update() {
      if (!this.isBuilt()) {
        return;
      }

      var conditionName;

      if (this.options.value) {
        conditionName = this.options.value.name;
      } else {
        conditionName = this.menu.hot.getTranslatedPhrase(C.FILTERS_CONDITIONS_NONE);
      }

      privatePool.get(this).captionElement.textContent = conditionName;

      _get(_getPrototypeOf(SelectUI.prototype), "update", this).call(this);
    }
    /**
     * Open select dropdown menu with available options.
     */

  }, {
    key: "openOptions",
    value: function openOptions() {
      var rect = this.element.getBoundingClientRect();

      if (this.menu) {
        this.menu.open();
        this.menu.setPosition({
          left: rect.left - 5,
          top: rect.top,
          width: rect.width,
          height: rect.height
        });
      }
    }
    /**
     * Close select dropdown menu.
     */

  }, {
    key: "closeOptions",
    value: function closeOptions() {
      if (this.menu) {
        this.menu.close();
      }
    }
    /**
     * On menu selected listener.
     *
     * @private
     * @param {Object} command Selected item
     */

  }, {
    key: "onMenuSelect",
    value: function onMenuSelect(command) {
      if (command.name !== SEPARATOR) {
        this.options.value = command;
        this.closeOptions();
        this.update();
        this.runLocalHooks('select', this.options.value);
      }
    }
    /**
     * On menu closed listener.
     *
     * @private
     */

  }, {
    key: "onMenuClosed",
    value: function onMenuClosed() {
      this.runLocalHooks('afterClose');
    }
    /**
     * On element click listener.
     *
     * @private
     */

  }, {
    key: "onClick",
    value: function onClick() {
      this.openOptions();
    }
    /**
     * Destroy instance.
     */

  }, {
    key: "destroy",
    value: function destroy() {
      if (this.menu) {
        this.menu.destroy();
        this.menu = null;
      }

      var _privatePool$get = privatePool.get(this),
          caption = _privatePool$get.caption,
          dropdown = _privatePool$get.dropdown;

      if (caption) {
        caption.destroy();
      }

      if (dropdown) {
        dropdown.destroy();
      }

      _get(_getPrototypeOf(SelectUI.prototype), "destroy", this).call(this);
    }
  }]);

  return SelectUI;
}(BaseUI);

export default SelectUI;