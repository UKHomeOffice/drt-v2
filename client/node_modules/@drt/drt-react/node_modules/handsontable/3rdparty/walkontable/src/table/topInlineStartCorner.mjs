import Table from "../table.mjs";
import stickyRowsTop from "./mixin/stickyRowsTop.mjs";
import stickyColumnsStart from "./mixin/stickyColumnsStart.mjs";
import { mixin } from "../../../../helpers/object.mjs";
import { CLONE_TOP_INLINE_START_CORNER } from "../overlay/index.mjs";
/**
 * Subclass of `Table` that provides the helper methods relevant to topInlineStartCornerOverlay
 * (in RTL mode the overlay sits on the right of the screen), implemented through mixins.
 *
 * @mixes stickyRowsTop
 * @mixes stickyColumnsStart
 */
class TopInlineStartCornerOverlayTable extends Table {
  /**
   * @param {TableDao} dataAccessObject The data access object.
   * @param {FacadeGetter} facadeGetter Function which return proper facade.
   * @param {DomBindings} domBindings Bindings into DOM.
   * @param {Settings} wtSettings The Walkontable settings.
   */
  constructor(dataAccessObject, facadeGetter, domBindings, wtSettings) {
    super(dataAccessObject, facadeGetter, domBindings, wtSettings, CLONE_TOP_INLINE_START_CORNER);
  }
}
mixin(TopInlineStartCornerOverlayTable, stickyRowsTop);
mixin(TopInlineStartCornerOverlayTable, stickyColumnsStart);
export default TopInlineStartCornerOverlayTable;