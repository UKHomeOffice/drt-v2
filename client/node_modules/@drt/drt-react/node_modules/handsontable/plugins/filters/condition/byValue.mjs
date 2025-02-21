import { registerCondition } from "../conditionRegisterer.mjs";
import { createArrayAssertion } from "../utils.mjs";
export const CONDITION_NAME = 'by_value';

/**
 * @param {object} dataRow The object which holds and describes the single cell value.
 * @param {Array} inputValues An array of values to compare with.
 * @param {Function} inputValues."0" A function to compare row's data.
 * @returns {boolean}
 */
export function condition(dataRow, _ref) {
  let [value] = _ref;
  return value(dataRow.value);
}
registerCondition(CONDITION_NAME, condition, {
  name: 'By value',
  inputsCount: 0,
  inputValuesDecorator(_ref2) {
    let [data] = _ref2;
    return [createArrayAssertion(data)];
  },
  showOperators: false
});