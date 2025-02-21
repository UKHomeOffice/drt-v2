import * as C from "../../../i18n/constants.mjs";
import { stringify } from "../../../helpers/mixed.mjs";
import { registerCondition } from "../conditionRegisterer.mjs";
export const CONDITION_NAME = 'eq';

/**
 * @param {object} dataRow The object which holds and describes the single cell value.
 * @param {Array} inputValues An array of values to compare with.
 * @param {Array} inputValues."0" Value to check if it same as row's data.
 * @returns {boolean}
 */
export function condition(dataRow, _ref) {
  let [value] = _ref;
  return stringify(dataRow.value).toLocaleLowerCase(dataRow.meta.locale) === stringify(value);
}
registerCondition(CONDITION_NAME, condition, {
  name: C.FILTERS_CONDITIONS_EQUAL,
  inputsCount: 1,
  showOperators: true
});