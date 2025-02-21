import { TimeEditor } from "../../editors/timeEditor/index.mjs";
import { timeRenderer } from "../../renderers/timeRenderer/index.mjs";
import { timeValidator } from "../../validators/timeValidator/index.mjs";
export const CELL_TYPE = 'time';
export const TimeCellType = {
  CELL_TYPE,
  editor: TimeEditor,
  renderer: timeRenderer,
  validator: timeValidator
};