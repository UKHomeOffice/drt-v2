import * as C from "../../../i18n/constants.mjs";
import { registerCondition } from "../conditionRegisterer.mjs";
export const CONDITION_NAME = 'gte';

/**
 * @param {object} dataRow The object which holds and describes the single cell value.
 * @param {Array} inputValues An array of values to compare with.
 * @param {*} inputValues."0" Condition value to compare numbers.
 * @returns {boolean}
 */
export function condition(dataRow, _ref) {
  let [value] = _ref;
  let conditionValue = value;
  if (dataRow.meta.type === 'numeric') {
    conditionValue = parseFloat(conditionValue, 10);
  }
  return dataRow.value >= conditionValue;
}
registerCondition(CONDITION_NAME, condition, {
  name: C.FILTERS_CONDITIONS_GREATER_THAN_OR_EQUAL,
  inputsCount: 1,
  showOperators: true
});