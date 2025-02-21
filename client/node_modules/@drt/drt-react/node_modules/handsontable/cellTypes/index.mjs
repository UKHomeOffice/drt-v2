import { AutocompleteCellType, CELL_TYPE as AUTOCOMPLETE_TYPE } from "./autocompleteType/index.mjs";
import { CheckboxCellType, CELL_TYPE as CHECKBOX_TYPE } from "./checkboxType/index.mjs";
import { DateCellType, CELL_TYPE as DATE_TYPE } from "./dateType/index.mjs";
import { DropdownCellType, CELL_TYPE as DROPDOWN_TYPE } from "./dropdownType/index.mjs";
import { HandsontableCellType, CELL_TYPE as HANDSONTABLE_TYPE } from "./handsontableType/index.mjs";
import { NumericCellType, CELL_TYPE as NUMERIC_TYPE } from "./numericType/index.mjs";
import { PasswordCellType, CELL_TYPE as PASSWORD_TYPE } from "./passwordType/index.mjs";
import { SelectCellType, CELL_TYPE as SELECT_TYPE } from "./selectType/index.mjs";
import { TextCellType, CELL_TYPE as TEXT_TYPE } from "./textType/index.mjs";
import { TimeCellType, CELL_TYPE as TIME_TYPE } from "./timeType/index.mjs";
import { registerCellType } from "./registry.mjs";
/**
 * Registers all available cell types.
 */
export function registerAllCellTypes() {
  registerCellType(AutocompleteCellType);
  registerCellType(CheckboxCellType);
  registerCellType(DateCellType);
  registerCellType(DropdownCellType);
  registerCellType(HandsontableCellType);
  registerCellType(NumericCellType);
  registerCellType(PasswordCellType);
  registerCellType(SelectCellType);
  registerCellType(TextCellType);
  registerCellType(TimeCellType);
}
export { AutocompleteCellType, AUTOCOMPLETE_TYPE, CheckboxCellType, CHECKBOX_TYPE, DateCellType, DATE_TYPE, DropdownCellType, DROPDOWN_TYPE, HandsontableCellType, HANDSONTABLE_TYPE, NumericCellType, NUMERIC_TYPE, PasswordCellType, PASSWORD_TYPE, SelectCellType, SELECT_TYPE, TextCellType, TEXT_TYPE, TimeCellType, TIME_TYPE };
export { getCellType, getRegisteredCellTypeNames, getRegisteredCellTypes, hasCellType, registerCellType } from "./registry.mjs";