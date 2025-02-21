import Table from "../table.mjs";
import stickyRowsBottom from "./mixin/stickyRowsBottom.mjs";
import calculatedColumns from "./mixin/calculatedColumns.mjs";
import { mixin } from "../../../../helpers/object.mjs";
import { CLONE_BOTTOM } from "../overlay/index.mjs";
/**
 * Subclass of `Table` that provides the helper methods relevant to BottomOverlay, implemented through mixins.
 *
 * @mixes stickyRowsBottom
 * @mixes calculatedColumns
 */
class BottomOverlayTable extends Table {
  /**
   * @param {TableDao} dataAccessObject The data access object.
   * @param {FacadeGetter} facadeGetter Function which return proper facade.
   * @param {DomBindings} domBindings Bindings into DOM.
   * @param {Settings} wtSettings The Walkontable settings.
   */
  constructor(dataAccessObject, facadeGetter, domBindings, wtSettings) {
    super(dataAccessObject, facadeGetter, domBindings, wtSettings, CLONE_BOTTOM);
  }
}
mixin(BottomOverlayTable, stickyRowsBottom);
mixin(BottomOverlayTable, calculatedColumns);
export default BottomOverlayTable;