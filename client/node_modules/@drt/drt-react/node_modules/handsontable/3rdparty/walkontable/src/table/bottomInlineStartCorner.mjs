import Table from "../table.mjs";
import stickyRowsBottom from "./mixin/stickyRowsBottom.mjs";
import stickyColumnsStart from "./mixin/stickyColumnsStart.mjs";
import { mixin } from "../../../../helpers/object.mjs";
import { CLONE_BOTTOM_INLINE_START_CORNER } from "../overlay/index.mjs";
/**
 * Subclass of `Table` that provides the helper methods relevant to bottomInlineStartCornerOverlay
 * (in RTL mode the overlay sits on the right of the screen), implemented through mixins.
 *
 * @mixes stickyRowsBottom
 * @mixes stickyColumnsStart
 */
class BottomInlineStartCornerOverlayTable extends Table {
  /**
   * @param {TableDao} dataAccessObject The data access object.
   * @param {FacadeGetter} facadeGetter Function which return proper facade.
   * @param {DomBindings} domBindings Bindings into DOM.
   * @param {Settings} wtSettings The Walkontable settings.
   */
  constructor(dataAccessObject, facadeGetter, domBindings, wtSettings) {
    super(dataAccessObject, facadeGetter, domBindings, wtSettings, CLONE_BOTTOM_INLINE_START_CORNER);
  }
}
mixin(BottomInlineStartCornerOverlayTable, stickyRowsBottom);
mixin(BottomInlineStartCornerOverlayTable, stickyColumnsStart);
export default BottomInlineStartCornerOverlayTable;