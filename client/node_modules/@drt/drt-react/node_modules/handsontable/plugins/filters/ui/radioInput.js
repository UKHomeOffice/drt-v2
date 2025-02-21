"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
var _object = require("../../../helpers/object");
var _base = require("./_base");
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
/**
 * @private
 * @class RadioInputUI
 */
var _input = /*#__PURE__*/new WeakMap();
var _label = /*#__PURE__*/new WeakMap();
class RadioInputUI extends _base.BaseUI {
  static get DEFAULTS() {
    return (0, _object.clone)({
      type: 'radio',
      tagName: 'input',
      className: 'htUIRadio',
      label: {}
    });
  }

  /**
   * The reference to the input element.
   *
   * @type {HTMLInputElement}
   */

  constructor(hotInstance, options) {
    super(hotInstance, (0, _object.extend)(RadioInputUI.DEFAULTS, options));
    _classPrivateFieldInitSpec(this, _input, void 0);
    /**
     * The reference to the label element.
     *
     * @type {HTMLLabelElement}
     */
    _classPrivateFieldInitSpec(this, _label, void 0);
  }

  /**
   * Build DOM structure.
   */
  build() {
    super.build();
    const label = this.hot.rootDocument.createElement('label');
    label.textContent = this.translateIfPossible(this.options.label.textContent);
    label.htmlFor = this.translateIfPossible(this.options.label.htmlFor);
    _classPrivateFieldSet(_label, this, label);
    _classPrivateFieldSet(_input, this, this._element.firstChild);
    _classPrivateFieldGet(_input, this).checked = this.options.checked;
    this._element.appendChild(label);
    this.update();
  }

  /**
   * Update element.
   */
  update() {
    if (!this.isBuilt()) {
      return;
    }
    _classPrivateFieldGet(_label, this).textContent = this.translateIfPossible(this.options.label.textContent);
  }

  /**
   * Check if radio button is checked.
   *
   * @returns {boolean}
   */
  isChecked() {
    return this.isBuilt() ? _classPrivateFieldGet(_input, this).checked : false;
  }

  /**
   * Set input checked attribute.
   *
   * @param {boolean} value Set the component state.
   */
  setChecked() {
    let value = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : true;
    if (this.isBuilt()) {
      _classPrivateFieldGet(_input, this).checked = value;
    }
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
exports.RadioInputUI = RadioInputUI;