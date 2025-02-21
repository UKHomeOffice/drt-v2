import * as C from "../../../i18n/constants.mjs";
import { stringify } from "../../../helpers/mixed.mjs";
import { registerCondition } from "../conditionRegisterer.mjs";
export const CONDITION_NAME = 'contains';

/**
 * @param {object} dataRow The object which holds and describes the single cell value.
 * @param {Array} inputValues An array of values to compare with.
 * @param {*} inputValues."0" A value to check if it occurs in the row's data.
 * @returns {boolean}
 */
export function condition(dataRow, _ref) {
  let [value] = _ref;
  return stringify(dataRow.value).toLocaleLowerCase(dataRow.meta.locale).indexOf(stringify(value)) >= 0;
}
registerCondition(CONDITION_NAME, condition, {
  name: C.FILTERS_CONDITIONS_CONTAINS,
  inputsCount: 1,
  showOperators: true
});