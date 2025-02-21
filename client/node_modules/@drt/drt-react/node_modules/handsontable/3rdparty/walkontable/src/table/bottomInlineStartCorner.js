"use strict";

exports.__esModule = true;
var _table = _interopRequireDefault(require("../table"));
var _stickyRowsBottom = _interopRequireDefault(require("./mixin/stickyRowsBottom"));
var _stickyColumnsStart = _interopRequireDefault(require("./mixin/stickyColumnsStart"));
var _object = require("../../../../helpers/object");
var _overlay = require("../overlay");
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
/**
 * Subclass of `Table` that provides the helper methods relevant to bottomInlineStartCornerOverlay
 * (in RTL mode the overlay sits on the right of the screen), implemented through mixins.
 *
 * @mixes stickyRowsBottom
 * @mixes stickyColumnsStart
 */
class BottomInlineStartCornerOverlayTable extends _table.default {
  /**
   * @param {TableDao} dataAccessObject The data access object.
   * @param {FacadeGetter} facadeGetter Function which return proper facade.
   * @param {DomBindings} domBindings Bindings into DOM.
   * @param {Settings} wtSettings The Walkontable settings.
   */
  constructor(dataAccessObject, facadeGetter, domBindings, wtSettings) {
    super(dataAccessObject, facadeGetter, domBindings, wtSettings, _overlay.CLONE_BOTTOM_INLINE_START_CORNER);
  }
}
(0, _object.mixin)(BottomInlineStartCornerOverlayTable, _stickyRowsBottom.default);
(0, _object.mixin)(BottomInlineStartCornerOverlayTable, _stickyColumnsStart.default);
var _default = exports.default = BottomInlineStartCornerOverlayTable;