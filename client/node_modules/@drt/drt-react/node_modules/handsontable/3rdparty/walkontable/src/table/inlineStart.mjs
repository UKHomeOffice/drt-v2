import Table from "../table.mjs";
import calculatedRows from "./mixin/calculatedRows.mjs";
import stickyColumnsStart from "./mixin/stickyColumnsStart.mjs";
import { mixin } from "../../../../helpers/object.mjs";
import { CLONE_INLINE_START } from "../overlay/index.mjs";
/**
 * Subclass of `Table` that provides the helper methods relevant to InlineStartOverlayTable, implemented through mixins.
 */
class InlineStartOverlayTable extends Table {
  /**
   * @param {TableDao} dataAccessObject The data access object.
   * @param {FacadeGetter} facadeGetter Function which return proper facade.
   * @param {DomBindings} domBindings Bindings into DOM.
   * @param {Settings} wtSettings The Walkontable settings.
   */
  constructor(dataAccessObject, facadeGetter, domBindings, wtSettings) {
    super(dataAccessObject, facadeGetter, domBindings, wtSettings, CLONE_INLINE_START);
  }
}
mixin(InlineStartOverlayTable, calculatedRows);
mixin(InlineStartOverlayTable, stickyColumnsStart);
export default InlineStartOverlayTable;