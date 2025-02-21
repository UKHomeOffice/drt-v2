import { SelectEditor } from "../../editors/selectEditor/index.mjs";
import { selectRenderer } from "../../renderers/selectRenderer/index.mjs";
export const CELL_TYPE = 'select';
export const SelectCellType = {
  CELL_TYPE,
  editor: SelectEditor,
  renderer: selectRenderer
};