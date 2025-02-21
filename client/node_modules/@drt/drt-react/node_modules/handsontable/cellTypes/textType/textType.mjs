import { TextEditor } from "../../editors/textEditor/index.mjs";
import { textRenderer } from "../../renderers/textRenderer/index.mjs";
export const CELL_TYPE = 'text';
export const TextCellType = {
  CELL_TYPE,
  editor: TextEditor,
  renderer: textRenderer
};