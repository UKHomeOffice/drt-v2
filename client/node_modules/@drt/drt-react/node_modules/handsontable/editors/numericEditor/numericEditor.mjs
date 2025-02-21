import { TextEditor } from "../textEditor/index.mjs";
export const EDITOR_TYPE = 'numeric';

/**
 * @private
 * @class NumericEditor
 */
export class NumericEditor extends TextEditor {
  static get EDITOR_TYPE() {
    return EDITOR_TYPE;
  }
}