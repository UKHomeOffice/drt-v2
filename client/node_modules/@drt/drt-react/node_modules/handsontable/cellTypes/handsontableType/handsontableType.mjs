import { HandsontableEditor } from "../../editors/handsontableEditor/index.mjs";
import { handsontableRenderer } from "../../renderers/handsontableRenderer/index.mjs";
export const CELL_TYPE = 'handsontable';
export const HandsontableCellType = {
  CELL_TYPE,
  editor: HandsontableEditor,
  // displays small gray arrow on right side of the cell
  renderer: handsontableRenderer
};