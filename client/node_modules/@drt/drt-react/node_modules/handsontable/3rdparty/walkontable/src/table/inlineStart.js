"use strict";

exports.__esModule = true;
var _table = _interopRequireDefault(require("../table"));
var _calculatedRows = _interopRequireDefault(require("./mixin/calculatedRows"));
var _stickyColumnsStart = _interopRequireDefault(require("./mixin/stickyColumnsStart"));
var _object = require("../../../../helpers/object");
var _overlay = require("../overlay");
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
/**
 * Subclass of `Table` that provides the helper methods relevant to InlineStartOverlayTable, implemented through mixins.
 */
class InlineStartOverlayTable extends _table.default {
  /**
   * @param {TableDao} dataAccessObject The data access object.
   * @param {FacadeGetter} facadeGetter Function which return proper facade.
   * @param {DomBindings} domBindings Bindings into DOM.
   * @param {Settings} wtSettings The Walkontable settings.
   */
  constructor(dataAccessObject, facadeGetter, domBindings, wtSettings) {
    super(dataAccessObject, facadeGetter, domBindings, wtSettings, _overlay.CLONE_INLINE_START);
  }
}
(0, _object.mixin)(InlineStartOverlayTable, _calculatedRows.default);
(0, _object.mixin)(InlineStartOverlayTable, _stickyColumnsStart.default);
var _default = exports.default = InlineStartOverlayTable;