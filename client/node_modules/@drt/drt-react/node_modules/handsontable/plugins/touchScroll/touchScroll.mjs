import "core-js/modules/es.error.cause.js";
import "core-js/modules/es.array.push.js";
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
import { addClass, removeClass } from "../../helpers/dom/element.mjs";
import { arrayEach } from "../../helpers/array.mjs";
import { BasePlugin } from "../base/index.mjs";
import { isTouchSupported } from "../../helpers/feature.mjs";
export const PLUGIN_KEY = 'touchScroll';
export const PLUGIN_PRIORITY = 200;

/**
 * @private
 * @plugin TouchScroll
 * @class TouchScroll
 */
var _TouchScroll_brand = /*#__PURE__*/new WeakSet();
export class TouchScroll extends BasePlugin {
  constructor() {
    super(...arguments);
    /**
     * After view render listener.
     */
    _classPrivateMethodInitSpec(this, _TouchScroll_brand);
    /**
     * Collection of scrollbars to update.
     *
     * @type {Array}
     */
    _defineProperty(this, "scrollbars", []);
    /**
     * Collection of overlays to update.
     *
     * @type {Array}
     */
    _defineProperty(this, "clones", []);
    /**
     * Flag which determines if collection of overlays should be refilled on every table render.
     *
     * @type {boolean}
     * @default false
     */
    _defineProperty(this, "lockedCollection", false);
    /**
     * Flag which determines if walkontable should freeze overlays while scrolling.
     *
     * @type {boolean}
     * @default false
     */
    _defineProperty(this, "freezeOverlays", false);
  }
  static get PLUGIN_KEY() {
    return PLUGIN_KEY;
  }
  static get PLUGIN_PRIORITY() {
    return PLUGIN_PRIORITY;
  }
  static get SETTING_KEYS() {
    return true;
  }
  /**
   * Check if plugin is enabled.
   *
   * @returns {boolean}
   */
  isEnabled() {
    return isTouchSupported();
  }

  /**
   * Enable the plugin.
   */
  enablePlugin() {
    if (this.enabled) {
      return;
    }
    this.addHook('afterViewRender', () => _assertClassBrand(_TouchScroll_brand, this, _onAfterViewRender).call(this));
    this.registerEvents();
    super.enablePlugin();
  }

  /**
   * Updates the plugin to use the latest options you have specified.
   */
  updatePlugin() {
    this.lockedCollection = false;
    super.updatePlugin();
  }

  /**
   * Disable plugin for this Handsontable instance.
   */
  disablePlugin() {
    super.disablePlugin();
  }

  /**
   * Register all necessary events.
   *
   * @private
   */
  registerEvents() {
    this.addHook('beforeTouchScroll', () => _assertClassBrand(_TouchScroll_brand, this, _onBeforeTouchScroll).call(this));
    this.addHook('afterMomentumScroll', () => _assertClassBrand(_TouchScroll_brand, this, _onAfterMomentumScroll).call(this));
  }
}
function _onAfterViewRender() {
  if (this.lockedCollection) {
    return;
  }
  const {
    topOverlay,
    bottomOverlay,
    inlineStartOverlay,
    topInlineStartCornerOverlay,
    bottomInlineStartCornerOverlay
  } = this.hot.view._wt.wtOverlays;
  this.lockedCollection = true;
  this.scrollbars.length = 0;
  this.scrollbars.push(topOverlay);
  if (bottomOverlay.clone) {
    this.scrollbars.push(bottomOverlay);
  }
  this.scrollbars.push(inlineStartOverlay);
  if (topInlineStartCornerOverlay) {
    this.scrollbars.push(topInlineStartCornerOverlay);
  }
  if (bottomInlineStartCornerOverlay && bottomInlineStartCornerOverlay.clone) {
    this.scrollbars.push(bottomInlineStartCornerOverlay);
  }
  this.clones = [];
  if (topOverlay.needFullRender) {
    this.clones.push(topOverlay.clone.wtTable.holder.parentNode);
  }
  if (bottomOverlay.needFullRender) {
    this.clones.push(bottomOverlay.clone.wtTable.holder.parentNode);
  }
  if (inlineStartOverlay.needFullRender) {
    this.clones.push(inlineStartOverlay.clone.wtTable.holder.parentNode);
  }
  if (topInlineStartCornerOverlay) {
    this.clones.push(topInlineStartCornerOverlay.clone.wtTable.holder.parentNode);
  }
  if (bottomInlineStartCornerOverlay && bottomInlineStartCornerOverlay.clone) {
    this.clones.push(bottomInlineStartCornerOverlay.clone.wtTable.holder.parentNode);
  }
}
/**
 * Touch scroll listener.
 */
function _onBeforeTouchScroll() {
  this.freezeOverlays = true;
  arrayEach(this.clones, clone => {
    addClass(clone, 'hide-tween');
  });
}
/**
 * After momentum scroll listener.
 */
function _onAfterMomentumScroll() {
  this.freezeOverlays = false;
  arrayEach(this.clones, clone => {
    removeClass(clone, 'hide-tween');
    addClass(clone, 'show-tween');
  });
  this.hot._registerTimeout(() => {
    arrayEach(this.clones, clone => {
      removeClass(clone, 'show-tween');
    });
  }, 400);
  arrayEach(this.scrollbars, scrollbar => {
    scrollbar.refresh();
    scrollbar.resetFixedPosition();
  });
  this.hot.view._wt.wtOverlays.syncScrollWithMaster();
}