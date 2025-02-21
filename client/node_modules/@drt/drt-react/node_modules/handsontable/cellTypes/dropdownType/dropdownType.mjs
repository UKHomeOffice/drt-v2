import { DropdownEditor } from "../../editors/dropdownEditor/index.mjs";
import { dropdownRenderer } from "../../renderers/dropdownRenderer/index.mjs";
import { dropdownValidator } from "../../validators/dropdownValidator/index.mjs";
export const CELL_TYPE = 'dropdown';
export const DropdownCellType = {
  CELL_TYPE,
  editor: DropdownEditor,
  renderer: dropdownRenderer,
  // displays small gray arrow on right side of the cell
  validator: dropdownValidator,
  filter: false,
  strict: true
};