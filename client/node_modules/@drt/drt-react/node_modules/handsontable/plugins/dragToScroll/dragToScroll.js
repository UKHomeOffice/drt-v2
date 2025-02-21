"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
var _base = require("../base");
var _event = require("../../helpers/dom/event");
var _element = require("../../helpers/dom/element");
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
const PLUGIN_KEY = exports.PLUGIN_KEY = 'dragToScroll';
const PLUGIN_PRIORITY = exports.PLUGIN_PRIORITY = 100;

/* eslint-disable jsdoc/require-description-complete-sentence */

/**
 * @description
 * Plugin used to scroll Handsontable by selecting a cell and dragging outside of the visible viewport.
 *
 *
 * @class DragToScroll
 * @plugin DragToScroll
 */
var _DragToScroll_brand = /*#__PURE__*/new WeakSet();
class DragToScroll extends _base.BasePlugin {
  constructor() {
    super(...arguments);
    /**
     * On after on cell/cellCorner mouse down listener.
     *
     * @param {MouseEvent} event The mouse event object.
     */
    _classPrivateMethodInitSpec(this, _DragToScroll_brand);
    /**
     * Size of an element and its position relative to the viewport,
     * e.g. {bottom: 449, height: 441, left: 8, right: 814, top: 8, width: 806, x: 8, y:8}.
     *
     * @type {DOMRect}
     */
    _defineProperty(this, "boundaries", null);
    /**
     * Callback function.
     *
     * @private
     * @type {Function}
     */
    _defineProperty(this, "callback", null);
    /**
     * Flag indicates mouseDown/mouseUp.
     *
     * @private
     * @type {boolean}
     */
    _defineProperty(this, "listening", false);
  }
  static get PLUGIN_KEY() {
    return PLUGIN_KEY;
  }
  static get PLUGIN_PRIORITY() {
    return PLUGIN_PRIORITY;
  }
  /**
   * Checks if the plugin is enabled in the handsontable settings. This method is executed in {@link Hooks#beforeInit}
   * hook and if it returns `true` then the {@link DragToScroll#enablePlugin} method is called.
   *
   * @returns {boolean}
   */
  isEnabled() {
    return !!this.hot.getSettings()[PLUGIN_KEY];
  }

  /**
   * Enables the plugin functionality for this Handsontable instance.
   */
  enablePlugin() {
    if (this.enabled) {
      return;
    }
    this.addHook('afterOnCellMouseDown', event => _assertClassBrand(_DragToScroll_brand, this, _setupListening).call(this, event));
    this.addHook('afterOnCellCornerMouseDown', event => _assertClassBrand(_DragToScroll_brand, this, _setupListening).call(this, event));
    this.registerEvents();
    super.enablePlugin();
  }

  /**
   * Updates the plugin's state.
   *
   * This method is executed when [`updateSettings()`](@/api/core.md#updatesettings) is invoked with any of the following configuration options:
   *  - [`dragToScroll`](@/api/options.md#dragtoscroll)
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
    this.unregisterEvents();
    super.disablePlugin();
  }

  /**
   * Sets the boundaries/dimensions of the scrollable viewport.
   *
   * @param {DOMRect|{left: number, right: number, top: number, bottom: number}} [boundaries] An object with
   * coordinates. Contains the window boundaries by default. The object is compatible with DOMRect.
   */
  setBoundaries() {
    let boundaries = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : {
      left: 0,
      right: this.hot.rootWindow.innerWidth,
      top: 0,
      bottom: this.hot.rootWindow.innerHeight
    };
    this.boundaries = boundaries;
  }

  /**
   * Changes callback function.
   *
   * @param {Function} callback The callback function.
   */
  setCallback(callback) {
    this.callback = callback;
  }

  /**
   * Checks if the mouse position (X, Y) is outside the viewport and fires a callback with calculated X an Y diffs
   * between passed boundaries.
   *
   * @param {number} x Mouse X coordinate to check.
   * @param {number} y Mouse Y coordinate to check.
   */
  check(x, y) {
    let diffX = 0;
    let diffY = 0;
    if (y < this.boundaries.top) {
      // y is less than top
      diffY = y - this.boundaries.top;
    } else if (y > this.boundaries.bottom) {
      // y is more than bottom
      diffY = y - this.boundaries.bottom;
    }
    if (x < this.boundaries.left) {
      // x is less than left
      diffX = x - this.boundaries.left;
    } else if (x > this.boundaries.right) {
      // x is more than right
      diffX = x - this.boundaries.right;
    }
    this.callback(diffX, diffY);
  }

  /**
   * Enables listening on `mousemove` event.
   *
   * @private
   */
  listen() {
    this.listening = true;
  }

  /**
   * Disables listening on `mousemove` event.
   *
   * @private
   */
  unlisten() {
    this.listening = false;
  }

  /**
   * Returns current state of listening.
   *
   * @private
   * @returns {boolean}
   */
  isListening() {
    return this.listening;
  }

  /**
   * Registers dom listeners.
   *
   * @private
   */
  registerEvents() {
    const {
      rootWindow
    } = this.hot;
    let frame = rootWindow;
    while (frame) {
      this.eventManager.addEventListener(frame.document, 'contextmenu', () => this.unlisten());
      this.eventManager.addEventListener(frame.document, 'mouseup', () => this.unlisten());
      this.eventManager.addEventListener(frame.document, 'mousemove', event => this.onMouseMove(event));
      frame = (0, _element.getParentWindow)(frame);
    }
  }

  /**
   * Unbinds the events used by the plugin.
   *
   * @private
   */
  unregisterEvents() {
    this.eventManager.clear();
  }
  /**
   * 'mouseMove' event callback.
   *
   * @private
   * @param {MouseEvent} event `mousemove` event properties.
   */
  onMouseMove(event) {
    if (!this.isListening()) {
      return;
    }
    this.check(event.clientX, event.clientY);
  }

  /**
   * Destroys the plugin instance.
   */
  destroy() {
    super.destroy();
  }
}
exports.DragToScroll = DragToScroll;
function _setupListening(event) {
  if ((0, _event.isRightClick)(event)) {
    return;
  }
  const scrollHandler = this.hot.view._wt.wtOverlays.topOverlay.mainTableScrollableElement;
  this.setBoundaries(scrollHandler !== this.hot.rootWindow ? scrollHandler.getBoundingClientRect() : undefined);
  this.setCallback((scrollX, scrollY) => {
    var _scrollHandler$scroll, _scrollHandler$scroll2;
    const horizontalScrollValue = (_scrollHandler$scroll = scrollHandler.scrollLeft) !== null && _scrollHandler$scroll !== void 0 ? _scrollHandler$scroll : scrollHandler.scrollX;
    const verticalScrollValue = (_scrollHandler$scroll2 = scrollHandler.scrollTop) !== null && _scrollHandler$scroll2 !== void 0 ? _scrollHandler$scroll2 : scrollHandler.scrollY;
    scrollHandler.scroll(horizontalScrollValue + Math.sign(scrollX) * 50, verticalScrollValue + Math.sign(scrollY) * 20);
  });
  this.listen();
}