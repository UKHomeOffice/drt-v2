import "core-js/modules/es.symbol";
import "core-js/modules/es.symbol.description";
import "core-js/modules/es.symbol.iterator";
import "core-js/modules/es.array.iterator";
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

import { clone, extend } from '../../../helpers/object';
import BaseUI from './_base';
var privatePool = new WeakMap();
/**
 * @class RadioInputUI
 * @util
 */

var RadioInputUI =
/*#__PURE__*/
function (_BaseUI) {
  _inherits(RadioInputUI, _BaseUI);

  _createClass(RadioInputUI, null, [{
    key: "DEFAULTS",
    get: function get() {
      return clone({
        type: 'radio',
        tagName: 'input',
        className: 'htUIRadio',
        label: {}
      });
    }
  }]);

  function RadioInputUI(hotInstance, options) {
    var _this;

    _classCallCheck(this, RadioInputUI);

    _this = _possibleConstructorReturn(this, _getPrototypeOf(RadioInputUI).call(this, hotInstance, extend(RadioInputUI.DEFAULTS, options)));
    privatePool.set(_assertThisInitialized(_this), {});
    return _this;
  }
  /**
   * Build DOM structure.
   */


  _createClass(RadioInputUI, [{
    key: "build",
    value: function build() {
      _get(_getPrototypeOf(RadioInputUI.prototype), "build", this).call(this);

      var priv = privatePool.get(this);
      priv.input = this._element.firstChild;
      var label = this.hot.rootDocument.createElement('label');
      label.textContent = this.translateIfPossible(this.options.label.textContent);
      label.htmlFor = this.translateIfPossible(this.options.label.htmlFor);
      priv.label = label;

      this._element.appendChild(label);

      this.update();
    }
    /**
     * Update element.
     */

  }, {
    key: "update",
    value: function update() {
      if (!this.isBuilt()) {
        return;
      }

      var priv = privatePool.get(this);
      priv.input.checked = this.options.checked;
      priv.label.textContent = this.translateIfPossible(this.options.label.textContent);
    }
    /**
     * Check if radio button is checked.
     *
     * @returns {Boolean}
     */

  }, {
    key: "isChecked",
    value: function isChecked() {
      return this.options.checked;
    }
    /**
     * Set input checked attribute
     *
     * @param value {Boolean} value
     */

  }, {
    key: "setChecked",
    value: function setChecked() {
      var value = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : true;
      this.options.checked = value;
      this.update();
    }
    /**
     * Focus element.
     */

  }, {
    key: "focus",
    value: function focus() {
      if (this.isBuilt()) {
        privatePool.get(this).input.focus();
      }
    }
  }]);

  return RadioInputUI;
}(BaseUI);

export default RadioInputUI;