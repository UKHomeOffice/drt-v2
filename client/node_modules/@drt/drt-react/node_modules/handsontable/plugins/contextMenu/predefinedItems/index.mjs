import { objectEach } from "../../../helpers/object.mjs";
import alignmentItem, { KEY as ALIGNMENT } from "./alignment.mjs";
import clearColumnItem, { KEY as CLEAR_COLUMN } from "./clearColumn.mjs";
import columnLeftItem, { KEY as COLUMN_LEFT } from "./columnLeft.mjs";
import columnRightItem, { KEY as COLUMN_RIGHT } from "./columnRight.mjs";
import readOnlyItem, { KEY as READ_ONLY } from "./readOnly.mjs";
import redoItem, { KEY as REDO } from "./redo.mjs";
import removeColumnItem, { KEY as REMOVE_COLUMN } from "./removeColumn.mjs";
import removeRowItem, { KEY as REMOVE_ROW } from "./removeRow.mjs";
import rowAboveItem, { KEY as ROW_ABOVE } from "./rowAbove.mjs";
import rowBelowItem, { KEY as ROW_BELOW } from "./rowBelow.mjs";
import separatorItem, { KEY as SEPARATOR } from "./separator.mjs";
import noItemsItem, { KEY as NO_ITEMS } from "./noItems.mjs";
import undoItem, { KEY as UNDO } from "./undo.mjs";
export { KEY as ALIGNMENT } from "./alignment.mjs";
export { KEY as CLEAR_COLUMN } from "./clearColumn.mjs";
export { KEY as COLUMN_LEFT } from "./columnLeft.mjs";
export { KEY as COLUMN_RIGHT } from "./columnRight.mjs";
export { KEY as READ_ONLY } from "./readOnly.mjs";
export { KEY as REDO } from "./redo.mjs";
export { KEY as REMOVE_COLUMN } from "./removeColumn.mjs";
export { KEY as REMOVE_ROW } from "./removeRow.mjs";
export { KEY as ROW_ABOVE } from "./rowAbove.mjs";
export { KEY as ROW_BELOW } from "./rowBelow.mjs";
export { KEY as SEPARATOR } from "./separator.mjs";
export { KEY as NO_ITEMS } from "./noItems.mjs";
export { KEY as UNDO } from "./undo.mjs";
export const ITEMS = [ROW_ABOVE, ROW_BELOW, COLUMN_LEFT, COLUMN_RIGHT, CLEAR_COLUMN, REMOVE_ROW, REMOVE_COLUMN, UNDO, REDO, READ_ONLY, ALIGNMENT, SEPARATOR, NO_ITEMS];
const _predefinedItems = {
  [SEPARATOR]: separatorItem,
  [NO_ITEMS]: noItemsItem,
  [ROW_ABOVE]: rowAboveItem,
  [ROW_BELOW]: rowBelowItem,
  [COLUMN_LEFT]: columnLeftItem,
  [COLUMN_RIGHT]: columnRightItem,
  [CLEAR_COLUMN]: clearColumnItem,
  [REMOVE_ROW]: removeRowItem,
  [REMOVE_COLUMN]: removeColumnItem,
  [UNDO]: undoItem,
  [REDO]: redoItem,
  [READ_ONLY]: readOnlyItem,
  [ALIGNMENT]: alignmentItem
};

/**
 * Gets new object with all predefined menu items.
 *
 * @returns {object}
 */
export function predefinedItems() {
  const items = {};
  objectEach(_predefinedItems, (itemFactory, key) => {
    items[key] = itemFactory();
  });
  return items;
}

/**
 * Add new predefined menu item to the collection.
 *
 * @param {string} key Menu command id.
 * @param {object} item Object command descriptor.
 */
export function addItem(key, item) {
  if (ITEMS.indexOf(key) === -1) {
    _predefinedItems[key] = item;
  }
}