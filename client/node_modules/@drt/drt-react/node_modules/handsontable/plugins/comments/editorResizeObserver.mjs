import "core-js/modules/es.error.cause.js";
import "core-js/modules/esnext.iterator.constructor.js";
import "core-js/modules/esnext.iterator.for-each.js";
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
import { mixin } from "../../helpers/object.mjs";
import localHooks from "../../mixins/localHooks.mjs";
/**
 * Module that observes the editor size after it has been resized by the user.
 *
 * @private
 * @class EditorResizeObserver
 */
var _ignoreInitialCall = /*#__PURE__*/new WeakMap();
var _observedElement = /*#__PURE__*/new WeakMap();
var _observer = /*#__PURE__*/new WeakMap();
var _EditorResizeObserver_brand = /*#__PURE__*/new WeakSet();
export class EditorResizeObserver {
  constructor() {
    /**
     * Listens for event from the ResizeObserver and forwards the through the local hooks.
     *
     * @param {*} entries The entries from the ResizeObserver.
     */
    _classPrivateMethodInitSpec(this, _EditorResizeObserver_brand);
    /**
     * The flag that indicates if the initial call should be ignored. It is used to prevent the initial call
     * that happens after the observer is attached to the element.
     *
     * @type {boolean}
     */
    _classPrivateFieldInitSpec(this, _ignoreInitialCall, true);
    /**
     * The element that is observed by the observer.
     *
     * @type {HTMLElement}
     */
    _classPrivateFieldInitSpec(this, _observedElement, null);
    /**
     * The ResizeObserver instance.
     *
     * @type {ResizeObserver}
     */
    _classPrivateFieldInitSpec(this, _observer, new ResizeObserver(entries => _assertClassBrand(_EditorResizeObserver_brand, this, _onResize).call(this, entries)));
  }
  /**
   * Sets the observed element.
   *
   * @param {HTMLElement} element The element to observe.
   */
  setObservedElement(element) {
    _classPrivateFieldSet(_observedElement, this, element);
  }

  /**
   * Stops observing the element.
   */
  unobserve() {
    _classPrivateFieldGet(_observer, this).unobserve(_classPrivateFieldGet(_observedElement, this));
  }

  /**
   * Starts observing the element.
   */
  observe() {
    _classPrivateFieldSet(_ignoreInitialCall, this, true);
    _classPrivateFieldGet(_observer, this).observe(_classPrivateFieldGet(_observedElement, this));
  }

  /**
   * Destroys the observer.
   */
  destroy() {
    _classPrivateFieldGet(_observer, this).disconnect();
  }
}
function _onResize(entries) {
  if (_classPrivateFieldGet(_ignoreInitialCall, this) || !Array.isArray(entries) || !entries.length) {
    _classPrivateFieldSet(_ignoreInitialCall, this, false);
    return;
  }
  entries.forEach(_ref => {
    let {
      borderBoxSize
    } = _ref;
    const {
      inlineSize,
      blockSize
    } = borderBoxSize[0];
    this.runLocalHooks('resize', inlineSize, blockSize);
  });
}
mixin(EditorResizeObserver, localHooks);