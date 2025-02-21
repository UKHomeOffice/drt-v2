import "core-js/modules/es.error.cause.js";
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
import { clone, extend } from "../../../helpers/object.mjs";
import { BaseUI } from "./_base.mjs";
/**
 * @private
 * @class LinkUI
 */
var _link = /*#__PURE__*/new WeakMap();
export class LinkUI extends BaseUI {
  static get DEFAULTS() {
    return clone({
      href: '#',
      tagName: 'a',
      tabIndex: -1,
      role: 'button'
    });
  }

  /**
   * The reference to the link element.
   *
   * @type {HTMLLinkElement}
   */

  constructor(hotInstance, options) {
    super(hotInstance, extend(LinkUI.DEFAULTS, options));
    _classPrivateFieldInitSpec(this, _link, void 0);
  }

  /**
   * Build DOM structure.
   */
  build() {
    super.build();
    _classPrivateFieldSet(_link, this, this._element.firstChild);
  }

  /**
   * Update element.
   */
  update() {
    if (!this.isBuilt()) {
      return;
    }
    _classPrivateFieldGet(_link, this).textContent = this.translateIfPossible(this.options.textContent);
  }

  /**
   * Focus element.
   */
  focus() {
    if (this.isBuilt()) {
      _classPrivateFieldGet(_link, this).focus();
    }
  }

  /**
   * Activate the element.
   */
  activate() {
    _classPrivateFieldGet(_link, this).click();
  }
}