import { CheckboxEditor } from "../../editors/checkboxEditor/index.mjs";
import { checkboxRenderer } from "../../renderers/checkboxRenderer/index.mjs";
export const CELL_TYPE = 'checkbox';
export const CheckboxCellType = {
  CELL_TYPE,
  editor: CheckboxEditor,
  renderer: checkboxRenderer
};