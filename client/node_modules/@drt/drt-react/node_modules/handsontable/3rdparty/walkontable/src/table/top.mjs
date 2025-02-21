import Table from "../table.mjs";
import stickyRowsTop from "./mixin/stickyRowsTop.mjs";
import calculatedColumns from "./mixin/calculatedColumns.mjs";
import { mixin } from "../../../../helpers/object.mjs";
import { CLONE_TOP } from "../overlay/index.mjs";
/**
 * Subclass of `Table` that provides the helper methods relevant to TopOverlay, implemented through mixins.
 *
 * @mixes stickyRowsTop
 * @mixes calculatedColumns
 */
class TopOverlayTable extends Table {
  /**
   * @param {TableDao} dataAccessObject The data access object.
   * @param {FacadeGetter} facadeGetter Function which return proper facade.
   * @param {DomBindings} domBindings Bindings into DOM.
   * @param {Settings} wtSettings The Walkontable settings.
   */
  constructor(dataAccessObject, facadeGetter, domBindings, wtSettings) {
    super(dataAccessObject, facadeGetter, domBindings, wtSettings, CLONE_TOP);
  }
}
mixin(TopOverlayTable, stickyRowsTop);
mixin(TopOverlayTable, calculatedColumns);
export default TopOverlayTable;