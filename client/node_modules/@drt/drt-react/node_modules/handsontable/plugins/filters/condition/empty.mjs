import * as C from "../../../i18n/constants.mjs";
import { registerCondition } from "../conditionRegisterer.mjs";
import { isEmpty } from "../../../helpers/mixed.mjs";
export const CONDITION_NAME = 'empty';

/**
 * @param {object} dataRow The object which holds and describes the single cell value.
 * @returns {boolean}
 */
export function condition(dataRow) {
  return isEmpty(dataRow.value);
}
registerCondition(CONDITION_NAME, condition, {
  name: C.FILTERS_CONDITIONS_EMPTY,
  inputsCount: 0,
  showOperators: true
});