import { DateEditor } from "../../editors/dateEditor/index.mjs";
import { dateRenderer } from "../../renderers/dateRenderer/index.mjs";
import { dateValidator } from "../../validators/dateValidator/index.mjs";
export const CELL_TYPE = 'date';
export const DateCellType = {
  CELL_TYPE,
  editor: DateEditor,
  // displays small gray arrow on right side of the cell
  renderer: dateRenderer,
  validator: dateValidator
};