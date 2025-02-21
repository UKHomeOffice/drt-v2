import { AutocompleteEditor, EDITOR_TYPE as AUTOCOMPLETE_EDITOR } from "./autocompleteEditor/index.mjs";
import { BaseEditor, EDITOR_TYPE as BASE_EDITOR } from "./baseEditor/index.mjs";
import { CheckboxEditor, EDITOR_TYPE as CHECKBOX_EDITOR } from "./checkboxEditor/index.mjs";
import { DateEditor, EDITOR_TYPE as DATE_EDITOR } from "./dateEditor/index.mjs";
import { DropdownEditor, EDITOR_TYPE as DROPDOWN_EDITOR } from "./dropdownEditor/index.mjs";
import { HandsontableEditor, EDITOR_TYPE as HANDSONTABLE_EDITOR } from "./handsontableEditor/index.mjs";
import { NumericEditor, EDITOR_TYPE as NUMERIC_EDITOR } from "./numericEditor/index.mjs";
import { PasswordEditor, EDITOR_TYPE as PASSWORD_EDITOR } from "./passwordEditor/index.mjs";
import { SelectEditor, EDITOR_TYPE as SELECT_EDITOR } from "./selectEditor/index.mjs";
import { TextEditor, EDITOR_TYPE as TEXT_EDITOR } from "./textEditor/index.mjs";
import { TimeEditor, EDITOR_TYPE as TIME_EDITOR } from "./timeEditor/index.mjs";
import { registerEditor } from "./registry.mjs";
/**
 * Registers all available editors.
 */
export function registerAllEditors() {
  registerEditor(BaseEditor);
  registerEditor(AutocompleteEditor);
  registerEditor(CheckboxEditor);
  registerEditor(DateEditor);
  registerEditor(DropdownEditor);
  registerEditor(HandsontableEditor);
  registerEditor(NumericEditor);
  registerEditor(PasswordEditor);
  registerEditor(SelectEditor);
  registerEditor(TextEditor);
  registerEditor(TimeEditor);
}
export { AutocompleteEditor, AUTOCOMPLETE_EDITOR, BaseEditor, BASE_EDITOR, CheckboxEditor, CHECKBOX_EDITOR, DateEditor, DATE_EDITOR, DropdownEditor, DROPDOWN_EDITOR, HandsontableEditor, HANDSONTABLE_EDITOR, NumericEditor, NUMERIC_EDITOR, PasswordEditor, PASSWORD_EDITOR, SelectEditor, SELECT_EDITOR, TextEditor, TEXT_EDITOR, TimeEditor, TIME_EDITOR };
export { RegisteredEditor, _getEditorInstance, getEditor, getEditorInstance, getRegisteredEditorNames, getRegisteredEditors, hasEditor, registerEditor } from "./registry.mjs";