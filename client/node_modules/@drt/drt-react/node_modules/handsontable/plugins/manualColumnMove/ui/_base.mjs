import "core-js/modules/es.error.cause.js";
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
import { isNumeric } from "../../../helpers/number.mjs";
import { toUpperCaseFirst } from "../../../helpers/string.mjs";
const STATE_INITIALIZED = 0;
const STATE_BUILT = 1;
const STATE_APPENDED = 2;
const UNIT = 'px';

/**
 * @class
 * @private
 */
class BaseUI {
  constructor(hotInstance) {
    /**
     * Instance of Handsontable.
     *
     * @type {Core}
     */
    _defineProperty(this, "hot", void 0);
    /**
     * DOM element representing the ui element.
     *
     * @type {HTMLElement}
     * @private
     */
    _defineProperty(this, "_element", null);
    /**
     * Flag which determines build state of element.
     *
     * @type {number}
     */
    _defineProperty(this, "state", STATE_INITIALIZED);
    /**
     * Defines the "start" physical CSS property name used within the class depending on what document
     * layout direction the library runs.
     *
     * @type {string}
     */
    _defineProperty(this, "inlineProperty", void 0);
    this.hot = hotInstance;
    this.inlineProperty = hotInstance.isRtl() ? 'right' : 'left';
  }

  /**
   * Add created UI elements to table.
   *
   * @param {HTMLElement} wrapper Element which are parent for our UI element.
   */
  appendTo(wrapper) {
    wrapper.appendChild(this._element);
    this.state = STATE_APPENDED;
  }

  /**
   * Method for create UI element. Only create, without append to table.
   */
  build() {
    if (this.state !== STATE_INITIALIZED) {
      return;
    }
    this._element = this.hot.rootDocument.createElement('div');
    this.state = STATE_BUILT;
  }

  /**
   * Method for remove UI element.
   */
  destroy() {
    if (this.isAppended()) {
      this._element.parentElement.removeChild(this._element);
    }
    this._element = null;
    this.state = STATE_INITIALIZED;
  }

  /**
   * Check if UI element are appended.
   *
   * @returns {boolean}
   */
  isAppended() {
    return this.state === STATE_APPENDED;
  }

  /**
   * Check if UI element are built.
   *
   * @returns {boolean}
   */
  isBuilt() {
    return this.state >= STATE_BUILT;
  }

  /**
   * Setter for position.
   *
   * @param {number} top New top position of the element.
   * @param {number} inlinePosition New left/right (depends on LTR/RTL document mode) position of the element.
   */
  setPosition(top, inlinePosition) {
    if (isNumeric(top)) {
      this._element.style.top = top + UNIT;
    }
    if (isNumeric(inlinePosition)) {
      this._element.style[this.inlineProperty] = inlinePosition + UNIT;
    }
  }

  /**
   * Getter for the element position.
   *
   * @returns {object} Object contains left and top position of the element.
   */
  getPosition() {
    const style = this._element.style;
    return {
      top: style.top ? parseInt(style.top, 10) : 0,
      start: style[this.inlineProperty] ? parseInt(style[this.inlineProperty], 10) : 0
    };
  }

  /**
   * Setter for the element size.
   *
   * @param {number} width New width of the element.
   * @param {number} height New height of the element.
   */
  setSize(width, height) {
    if (isNumeric(width)) {
      this._element.style.width = width + UNIT;
    }
    if (isNumeric(height)) {
      this._element.style.height = height + UNIT;
    }
  }

  /**
   * Getter for the element position.
   *
   * @returns {object} Object contains height and width of the element.
   */
  getSize() {
    return {
      width: this._element.style.width ? parseInt(this._element.style.width, 10) : 0,
      height: this._element.style.height ? parseInt(this._element.style.height, 10) : 0
    };
  }

  /**
   * Setter for the element offset. Offset means marginTop and marginLeft of the element.
   *
   * @param {number} top New margin top of the element.
   * @param {number} inlineOffset New margin left/right (depends on LTR/RTL document mode) of the element.
   */
  setOffset(top, inlineOffset) {
    if (isNumeric(top)) {
      this._element.style.marginTop = top + UNIT;
    }
    if (isNumeric(inlineOffset)) {
      this._element.style[`margin${toUpperCaseFirst(this.inlineProperty)}`] = inlineOffset + UNIT;
    }
  }

  /**
   * Getter for the element offset.
   *
   * @returns {object} Object contains top and left offset of the element.
   */
  getOffset() {
    const style = this._element.style;
    const inlineProp = `margin${toUpperCaseFirst(this.inlineProperty)}`;
    return {
      top: style.marginTop ? parseInt(style.marginTop, 10) : 0,
      start: style[inlineProp] ? parseInt(style[inlineProp], 10) : 0
    };
  }
}
export default BaseUI;