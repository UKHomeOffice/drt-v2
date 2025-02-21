import "core-js/modules/es.error.cause.js";
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
import { addClass } from "../../../helpers/dom/element.mjs";
import { clone, extend } from "../../../helpers/object.mjs";
import { BaseUI } from "./_base.mjs";
/**
 * @private
 * @class InputUI
 */
var _input = /*#__PURE__*/new WeakMap();
var _InputUI_brand = /*#__PURE__*/new WeakSet();
export class InputUI extends BaseUI {
  static get DEFAULTS() {
    return clone({
      placeholder: '',
      type: 'text',
      tagName: 'input',
      tabIndex: -1
    });
  }

  /**
   * The reference to the input element.
   *
   * @type {HTMLInputElement}
   */

  constructor(hotInstance, options) {
    super(hotInstance, extend(InputUI.DEFAULTS, options));
    /**
     * OnKeyup listener.
     *
     * @param {Event} event The mouse event object.
     */
    _classPrivateMethodInitSpec(this, _InputUI_brand);
    _classPrivateFieldInitSpec(this, _input, void 0);
    this.registerHooks();
  }

  /**
   * Register all necessary hooks.
   */
  registerHooks() {
    this.addLocalHook('keyup', event => _assertClassBrand(_InputUI_brand, this, _onKeyup).call(this, event));
  }

  /**
   * Build DOM structure.
   */
  build() {
    super.build();
    const icon = this.hot.rootDocument.createElement('div');
    _classPrivateFieldSet(_input, this, this._element.firstChild);
    addClass(this._element, 'htUIInput');
    addClass(icon, 'htUIInputIcon');
    this._element.appendChild(icon);
    this.update();
  }

  /**
   * Update element.
   */
  update() {
    if (!this.isBuilt()) {
      return;
    }
    _classPrivateFieldGet(_input, this).type = this.options.type;
    _classPrivateFieldGet(_input, this).placeholder = this.translateIfPossible(this.options.placeholder);
    _classPrivateFieldGet(_input, this).value = this.translateIfPossible(this.options.value);
  }

  /**
   * Focus element.
   */
  focus() {
    if (this.isBuilt()) {
      _classPrivateFieldGet(_input, this).focus();
    }
  }
}
function _onKeyup(event) {
  this.options.value = event.target.value;
}