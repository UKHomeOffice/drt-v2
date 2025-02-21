import "core-js/modules/esnext.iterator.constructor.js";
import "core-js/modules/esnext.iterator.some.js";
import * as C from "../../../i18n/constants.mjs";
import { registerOperation } from "../logicalOperationRegisterer.mjs";
export const OPERATION_ID = 'disjunction';
export const SHORT_NAME_FOR_COMPONENT = C.FILTERS_LABELS_DISJUNCTION;
// (p OR q OR w OR x OR...) === TRUE?

/**
 * @param {Array} conditions An array with values to check.
 * @param {*} value The comparable value.
 * @returns {boolean}
 */
export function operationResult(conditions, value) {
  return conditions.some(condition => condition.func(value));
}
registerOperation(OPERATION_ID, SHORT_NAME_FOR_COMPONENT, operationResult);