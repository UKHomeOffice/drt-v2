"use strict";

exports.__esModule = true;
var _table = _interopRequireDefault(require("../table"));
var _stickyRowsTop = _interopRequireDefault(require("./mixin/stickyRowsTop"));
var _stickyColumnsStart = _interopRequireDefault(require("./mixin/stickyColumnsStart"));
var _object = require("../../../../helpers/object");
var _overlay = require("../overlay");
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
/**
 * Subclass of `Table` that provides the helper methods relevant to topInlineStartCornerOverlay
 * (in RTL mode the overlay sits on the right of the screen), implemented through mixins.
 *
 * @mixes stickyRowsTop
 * @mixes stickyColumnsStart
 */
class TopInlineStartCornerOverlayTable extends _table.default {
  /**
   * @param {TableDao} dataAccessObject The data access object.
   * @param {FacadeGetter} facadeGetter Function which return proper facade.
   * @param {DomBindings} domBindings Bindings into DOM.
   * @param {Settings} wtSettings The Walkontable settings.
   */
  constructor(dataAccessObject, facadeGetter, domBindings, wtSettings) {
    super(dataAccessObject, facadeGetter, domBindings, wtSettings, _overlay.CLONE_TOP_INLINE_START_CORNER);
  }
}
(0, _object.mixin)(TopInlineStartCornerOverlayTable, _stickyRowsTop.default);
(0, _object.mixin)(TopInlineStartCornerOverlayTable, _stickyColumnsStart.default);
var _default = exports.default = TopInlineStartCornerOverlayTable;