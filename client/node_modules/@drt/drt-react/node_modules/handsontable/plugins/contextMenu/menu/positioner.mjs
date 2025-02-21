import "core-js/modules/es.error.cause.js";
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
import { Cursor } from "./cursor.mjs";
/**
 * Helper class for positioning the menu and submenus in the correct place relative to the
 * cursor position (DOM element or mouse coordinates).
 *
 * @private
 * @class Positioner
 */
var _container = /*#__PURE__*/new WeakMap();
var _parentContainer = /*#__PURE__*/new WeakMap();
var _cursor = /*#__PURE__*/new WeakMap();
var _keepInViewport = /*#__PURE__*/new WeakMap();
var _offset = /*#__PURE__*/new WeakMap();
export class Positioner {
  constructor(keepInViewport) {
    /**
     * The menu container element the positioning will be applied to.
     *
     * @type {HTMLElement}
     */
    _classPrivateFieldInitSpec(this, _container, void 0);
    /**
     * For positioning the submenu, the parent element is used to calculate offsets to ensure that submenu
     * is positioned right next to the parent menu.
     *
     * @type {HTMLElement}
     */
    _classPrivateFieldInitSpec(this, _parentContainer, void 0);
    /**
     * The instance of the Cursor class.
     *
     * @type {Cursor}
     */
    _classPrivateFieldInitSpec(this, _cursor, void 0);
    /**
     * Enabling the option allows changing the position calculation so that the menus (or submenus)
     * are always placed within the visible viewport of the browser.
     *
     * @type {boolean}
     */
    _classPrivateFieldInitSpec(this, _keepInViewport, false);
    /**
     * Allows apply the position offset for directions.
     *
     * @type {{above: number, below: number, left: number, right: number}}
     */
    _classPrivateFieldInitSpec(this, _offset, {
      above: 0,
      below: 0,
      left: 0,
      right: 0
    });
    _classPrivateFieldSet(_keepInViewport, this, keepInViewport);
  }

  /**
   * Sets offset position for specified directions (`above`, `below`, `left` or `right`).
   *
   * @param {'above' | 'below' | 'left' | 'right'} direction A direction name.
   * @param {number} [offset=0] Offset value.
   * @returns {Positioner}
   */
  setOffset(direction) {
    let offset = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : 0;
    _classPrivateFieldGet(_offset, this)[direction] = offset;
    return this;
  }

  /**
   * Sets the menu element to work with. The element can be owned by the main menu or the submenu.
   *
   * @param {HTMLElement} container The menu container element.
   * @returns {Positioner}
   */
  setElement(container) {
    _classPrivateFieldSet(_container, this, container);
    return this;
  }

  /**
   * Sets the parent menu element to work with.
   *
   * @param {HTMLElement} container The parent menu container element.
   * @returns {Positioner}
   */
  setParentElement(container) {
    _classPrivateFieldSet(_parentContainer, this, container);
    return this;
  }

  /**
   * Updates the menu position.
   *
   * @param {object|MouseEvent} coords The literal object with `top`, `left`, `width` and `height` props or a
   * mouse event object.
   */
  updatePosition(coords) {
    _classPrivateFieldSet(_cursor, this, new Cursor(coords, _classPrivateFieldGet(_container, this).ownerDocument.defaultView));
    if (_classPrivateFieldGet(_keepInViewport, this)) {
      if (_classPrivateFieldGet(_cursor, this).fitsBelow(_classPrivateFieldGet(_container, this))) {
        this.setPositionBelowCursor();
      } else if (_classPrivateFieldGet(_cursor, this).fitsAbove(_classPrivateFieldGet(_container, this))) {
        this.setPositionAboveCursor();
      } else {
        this.setPositionBelowCursor();
      }
      this.updateHorizontalPosition();
    } else {
      this.setPositionBelowCursor();
      this.setPositionOnRightOfCursor();
    }
  }

  /**
   * Updates the menu horizontal position.
   */
  updateHorizontalPosition() {
    if (_classPrivateFieldGet(_container, this).dir === 'rtl') {
      if (_classPrivateFieldGet(_cursor, this).fitsOnLeft(_classPrivateFieldGet(_container, this))) {
        this.setPositionOnLeftOfCursor();
      } else {
        this.setPositionOnRightOfCursor();
      }
    } else if (_classPrivateFieldGet(_cursor, this).fitsOnRight(_classPrivateFieldGet(_container, this))) {
      this.setPositionOnRightOfCursor();
    } else {
      this.setPositionOnLeftOfCursor();
    }
  }

  /**
   * Sets the menu position above the cursor object.
   */
  setPositionAboveCursor() {
    let top = _classPrivateFieldGet(_offset, this).above + _classPrivateFieldGet(_cursor, this).top - _classPrivateFieldGet(_container, this).offsetHeight;
    if (_classPrivateFieldGet(_parentContainer, this)) {
      top = _classPrivateFieldGet(_cursor, this).top + _classPrivateFieldGet(_cursor, this).cellHeight - _classPrivateFieldGet(_container, this).offsetHeight + 3;
    }
    _classPrivateFieldGet(_container, this).style.top = `${top}px`;
  }

  /**
   * Sets the menu position below the cursor object.
   */
  setPositionBelowCursor() {
    let top = _classPrivateFieldGet(_offset, this).below + _classPrivateFieldGet(_cursor, this).top + 1;
    if (_classPrivateFieldGet(_parentContainer, this)) {
      top = _classPrivateFieldGet(_cursor, this).top - 1;
    }
    _classPrivateFieldGet(_container, this).style.top = `${top}px`;
  }

  /**
   * Sets the menu position on the right of the cursor object.
   */
  setPositionOnRightOfCursor() {
    let left = _classPrivateFieldGet(_cursor, this).left;
    if (_classPrivateFieldGet(_parentContainer, this)) {
      const rootWindow = _classPrivateFieldGet(_parentContainer, this).ownerDocument.defaultView;
      const borderRightWidth = Number.parseInt(rootWindow.getComputedStyle(_classPrivateFieldGet(_parentContainer, this).querySelector('.htCore')).borderRightWidth, 10);
      left += _classPrivateFieldGet(_cursor, this).cellWidth + borderRightWidth;
    } else {
      left += _classPrivateFieldGet(_offset, this).right;
    }
    _classPrivateFieldGet(_container, this).style.left = `${left}px`;
  }

  /**
   * Sets the menu position on the left of the cursor object.
   */
  setPositionOnLeftOfCursor() {
    let left = _classPrivateFieldGet(_offset, this).left + _classPrivateFieldGet(_cursor, this).left - _classPrivateFieldGet(_container, this).offsetWidth;
    if (_classPrivateFieldGet(_parentContainer, this)) {
      const rootWindow = _classPrivateFieldGet(_parentContainer, this).ownerDocument.defaultView;
      const borderLeftWidth = Number.parseInt(rootWindow.getComputedStyle(_classPrivateFieldGet(_parentContainer, this).querySelector('.htCore')).borderLeftWidth, 10);
      left -= borderLeftWidth;
    }
    _classPrivateFieldGet(_container, this).style.left = `${left}px`;
  }
}