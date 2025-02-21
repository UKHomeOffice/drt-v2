"use strict";

exports.__esModule = true;
var _element = require("../../../../helpers/dom/element");
var _bottomInlineStartCorner = _interopRequireDefault(require("../table/bottomInlineStartCorner"));
var _base = require("./_base");
var _constants = require("./constants");
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
/**
 * @class BottomInlineStartCornerOverlay
 */
class BottomInlineStartCornerOverlay extends _base.Overlay {
  /**
   * @param {Walkontable} wotInstance The Walkontable instance. @TODO refactoring: check if can be deleted.
   * @param {FacadeGetter} facadeGetter Function which return proper facade.
   * @param {Settings} wtSettings The Walkontable settings.
   * @param {DomBindings} domBindings Dom elements bound to the current instance.
   * @param {BottomOverlay} bottomOverlay The instance of the Top overlay.
   * @param {InlineStartOverlay} inlineStartOverlay The instance of the InlineStart overlay.
   */
  constructor(wotInstance, facadeGetter, wtSettings, domBindings, bottomOverlay, inlineStartOverlay) {
    super(wotInstance, facadeGetter, _constants.CLONE_BOTTOM_INLINE_START_CORNER, wtSettings, domBindings);
    this.bottomOverlay = bottomOverlay;
    this.inlineStartOverlay = inlineStartOverlay;
  }

  /**
   * Factory method to create a subclass of `Table` that is relevant to this overlay.
   *
   * @see Table#constructor
   * @param {...*} args Parameters that will be forwarded to the `Table` constructor.
   * @returns {BottomInlineStartCornerOverlayTable}
   */
  createTable() {
    for (var _len = arguments.length, args = new Array(_len), _key = 0; _key < _len; _key++) {
      args[_key] = arguments[_key];
    }
    return new _bottomInlineStartCorner.default(...args);
  }

  /**
   * Checks if overlay should be fully rendered.
   *
   * @returns {boolean}
   */
  shouldBeRendered() {
    return this.wtSettings.getSetting('shouldRenderBottomOverlay') && this.wtSettings.getSetting('shouldRenderInlineStartOverlay');
  }

  /**
   * Updates the corner overlay position.
   *
   * @returns {boolean}
   */
  resetFixedPosition() {
    const {
      wot
    } = this;
    this.updateTrimmingContainer();
    if (!wot.wtTable.holder.parentNode) {
      // removed from DOM
      return false;
    }
    const overlayRoot = this.clone.wtTable.holder.parentNode;
    overlayRoot.style.top = '';
    if (this.trimmingContainer === this.domBindings.rootWindow) {
      const inlineStartOffset = this.inlineStartOverlay.getOverlayOffset();
      const bottom = this.bottomOverlay.getOverlayOffset();
      overlayRoot.style[this.isRtl() ? 'right' : 'left'] = `${inlineStartOffset}px`;
      overlayRoot.style.bottom = `${bottom}px`;
    } else {
      (0, _element.resetCssTransform)(overlayRoot);
      this.repositionOverlay();
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

  /**
   * Reposition the overlay.
   */
  repositionOverlay() {
    const {
      wtTable,
      wtViewport
    } = this.wot;
    const {
      rootDocument
    } = this.domBindings;
    const cloneRoot = this.clone.wtTable.holder.parentNode;
    let bottomOffset = 0;
    if (!wtViewport.hasVerticalScroll()) {
      bottomOffset += wtViewport.getWorkspaceHeight() - wtTable.getTotalHeight();
    }
    if (wtViewport.hasVerticalScroll() && wtViewport.hasHorizontalScroll()) {
      bottomOffset += (0, _element.getScrollbarWidth)(rootDocument);
    }
    cloneRoot.style.bottom = `${bottomOffset}px`;
  }
}
exports.BottomInlineStartCornerOverlay = BottomInlineStartCornerOverlay;