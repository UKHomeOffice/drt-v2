import "core-js/modules/es.error.cause.js";
var _ActionBarComponent;
import "core-js/modules/es.array.push.js";
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
import { addClass } from "../../../helpers/dom/element.mjs";
import { arrayEach } from "../../../helpers/array.mjs";
import * as C from "../../../i18n/constants.mjs";
import { BaseComponent } from "./_base.mjs";
import { InputUI } from "../ui/input.mjs";
/**
 * @private
 * @class ActionBarComponent
 */
var _ActionBarComponent_brand = /*#__PURE__*/new WeakSet();
export class ActionBarComponent extends BaseComponent {
  static get BUTTON_OK() {
    return 'ok';
  }
  static get BUTTON_CANCEL() {
    return 'cancel';
  }
  constructor(hotInstance, options) {
    super(hotInstance, {
      id: options.id,
      stateless: true
    });
    /**
     * On button click listener.
     *
     * @param {Event} event DOM event.
     * @param {InputUI} button InputUI object.
     */
    _classPrivateMethodInitSpec(this, _ActionBarComponent_brand);
    /**
     * The name of the component.
     *
     * @type {string}
     */
    _defineProperty(this, "name", '');
    this.name = options.name;
    this.elements.push(new InputUI(this.hot, {
      type: 'button',
      value: C.FILTERS_BUTTONS_OK,
      className: 'htUIButton htUIButtonOK',
      identifier: ActionBarComponent.BUTTON_OK
    }));
    this.elements.push(new InputUI(this.hot, {
      type: 'button',
      value: C.FILTERS_BUTTONS_CANCEL,
      className: 'htUIButton htUIButtonCancel',
      identifier: ActionBarComponent.BUTTON_CANCEL
    }));
    this.registerHooks();
  }

  /**
   * Register all necessary hooks.
   *
   * @private
   */
  registerHooks() {
    arrayEach(this.elements, element => {
      element.addLocalHook('click', (event, button) => _assertClassBrand(_ActionBarComponent_brand, this, _onButtonClick).call(this, event, button));
    });
  }

  /**
   * Get menu object descriptor.
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
      renderer: (hot, wrapper) => {
        addClass(wrapper.parentNode, 'htFiltersMenuActionBar');
        arrayEach(this.elements, ui => wrapper.appendChild(ui.element));
        return wrapper;
      }
    };
  }

  /**
   * Fire accept event.
   */
  accept() {
    this.runLocalHooks('accept');
  }

  /**
   * Fire cancel event.
   */
  cancel() {
    this.runLocalHooks('cancel');
  }
}
_ActionBarComponent = ActionBarComponent;
function _onButtonClick(event, button) {
  if (button.options.identifier === _ActionBarComponent.BUTTON_OK) {
    this.accept();
  } else {
    this.cancel();
  }
}