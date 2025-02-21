import { NumericEditor } from "../../editors/numericEditor/index.mjs";
import { numericRenderer } from "../../renderers/numericRenderer/index.mjs";
import { numericValidator } from "../../validators/numericValidator/index.mjs";
export const CELL_TYPE = 'numeric';
export const NumericCellType = {
  CELL_TYPE,
  editor: NumericEditor,
  renderer: numericRenderer,
  validator: numericValidator,
  dataType: 'number'
};