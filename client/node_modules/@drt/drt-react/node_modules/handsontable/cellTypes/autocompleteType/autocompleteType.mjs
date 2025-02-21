import { AutocompleteEditor } from "../../editors/autocompleteEditor/index.mjs";
import { autocompleteRenderer } from "../../renderers/autocompleteRenderer/index.mjs";
import { autocompleteValidator } from "../../validators/autocompleteValidator/index.mjs";
export const CELL_TYPE = 'autocomplete';
export const AutocompleteCellType = {
  CELL_TYPE,
  editor: AutocompleteEditor,
  renderer: autocompleteRenderer,
  validator: autocompleteValidator
};