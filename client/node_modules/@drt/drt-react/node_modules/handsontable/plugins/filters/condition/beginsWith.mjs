import * as C from "../../../i18n/constants.mjs";
import { stringify } from "../../../helpers/mixed.mjs";
import { registerCondition } from "../conditionRegisterer.mjs";
export const CONDITION_NAME = 'begins_with';

/**
 * @param {object} dataRow The object which holds and describes the single cell value.
 * @param {Array} inputValues An array of values to compare with.
 * @param {*} inputValues."0" Value to check if it occurs at the beginning.
 * @returns {boolean}
 */
export function condition(dataRow, _ref) {
  let [value] = _ref;
  return stringify(dataRow.value).toLocaleLowerCase(dataRow.meta.locale).startsWith(stringify(value));
}
registerCondition(CONDITION_NAME, condition, {
  name: C.FILTERS_CONDITIONS_BEGINS_WITH,
  inputsCount: 1,
  showOperators: true
});