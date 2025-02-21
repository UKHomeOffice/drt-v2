import { PasswordEditor } from "../../editors/passwordEditor/index.mjs";
import { passwordRenderer } from "../../renderers/passwordRenderer/index.mjs";
export const CELL_TYPE = 'password';
export const PasswordCellType = {
  CELL_TYPE,
  editor: PasswordEditor,
  renderer: passwordRenderer,
  copyable: false
};