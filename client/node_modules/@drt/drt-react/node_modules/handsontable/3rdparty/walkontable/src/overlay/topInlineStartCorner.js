"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
var _element = require("../../../../helpers/dom/element");
var _topInlineStartCorner = _interopRequireDefault(require("../table/topInlineStartCorner"));
var _base = require("./_base");
var _constants = require("./constants");
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
/**
 * @class TopInlineStartCornerOverlay
 */
class TopInlineStartCornerOverlay extends _base.Overlay {
  /**
   * @param {Walkontable} wotInstance The Walkontable instance. @TODO refactoring: check if can be deleted.
   * @param {FacadeGetter} facadeGetter Function which return proper facade.
   * @param {Settings} wtSettings The Walkontable settings.
   * @param {DomBindings} domBindings Dom elements bound to the current instance.
   * @param {TopOverlay} topOverlay The instance of the Top overlay.
   * @param {InlineStartOverlay} inlineStartOverlay The instance of the InlineStart overlay.
   */
  constructor(wotInstance, facadeGetter, wtSettings, domBindings, topOverlay, inlineStartOverlay) {
    super(wotInstance, facadeGetter, _constants.CLONE_TOP_INLINE_START_CORNER, wtSettings, domBindings);
    /**
     * The instance of the Top overlay.
     *
     * @type {TopOverlay}
     */
    _defineProperty(this, "topOverlay", void 0);
    /**
     * The instance of the InlineStart overlay.
     *
     * @type {InlineStartOverlay}
     */
    _defineProperty(this, "inlineStartOverlay", void 0);
    this.topOverlay = topOverlay;
    this.inlineStartOverlay = inlineStartOverlay;
  }

  /**
   * Factory method to create a subclass of `Table` that is relevant to this overlay.
   *
   * @see Table#constructor
   * @param {...*} args Parameters that will be forwarded to the `Table` constructor.
   * @returns {TopInlineStartCornerOverlayTable}
   */
  createTable() {
    for (var _len = arguments.length, args = new Array(_len), _key = 0; _key < _len; _key++) {
      args[_key] = arguments[_key];
    }
    return new _topInlineStartCorner.default(...args);
  }

  /**
   * Checks if overlay should be fully rendered.
   *
   * @returns {boolean}
   */
  shouldBeRendered() {
    return this.wtSettings.getSetting('shouldRenderTopOverlay') && this.wtSettings.getSetting('shouldRenderInlineStartOverlay');
  }

  /**
   * Updates the corner overlay position.
   *
   * @returns {boolean}
   */
  resetFixedPosition() {
    this.updateTrimmingContainer();
    if (!this.wot.wtTable.holder.parentNode) {
      // removed from DOM
      return false;
    }
    const overlayRoot = this.clone.wtTable.holder.parentNode;
    if (this.trimmingContainer === this.domBindings.rootWindow) {
      const left = this.inlineStartOverlay.getOverlayOffset() * (this.isRtl() ? -1 : 1);
      const top = this.topOverlay.getOverlayOffset();
      (0, _element.setOverlayPosition)(overlayRoot, `${left}px`, `${top}px`);
    } else {
      (0, _element.resetCssTransform)(overlayRoot);
    }
    let tableHeight = (0, _element.outerHeight)(this.clone.wtTable.TABLE);
    const tableWidth = (0, _element.outerWidth)(this.clone.wtTable.TABLE);
    if (!this.wot.wtTable.hasDefinedSize()) {
      tableHeight = 0;
    }
    overlayRoot.style.height = `${tableHeight}px`;
    overlayRoot.style.width = `${tableWidth}px`;
    return false;
  }
}
exports.TopInlineStartCornerOverlay = TopInlineStartCornerOverlay;