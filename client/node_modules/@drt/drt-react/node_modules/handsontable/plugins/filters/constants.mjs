import "core-js/modules/es.array.push.js";
import { clone } from "../../helpers/object.mjs";
import { arrayEach } from "../../helpers/array.mjs";
import { SEPARATOR } from "../contextMenu/predefinedItems/index.mjs";
import { getConditionDescriptor } from "./conditionRegisterer.mjs";
import { CONDITION_NAME as CONDITION_NONE } from "./condition/none.mjs";
import { CONDITION_NAME as CONDITION_EMPTY } from "./condition/empty.mjs";
import { CONDITION_NAME as CONDITION_NOT_EMPTY } from "./condition/notEmpty.mjs";
import { CONDITION_NAME as CONDITION_EQUAL } from "./condition/equal.mjs";
import { CONDITION_NAME as CONDITION_NOT_EQUAL } from "./condition/notEqual.mjs";
import { CONDITION_NAME as CONDITION_GREATER_THAN } from "./condition/greaterThan.mjs";
import { CONDITION_NAME as CONDITION_GREATER_THAN_OR_EQUAL } from "./condition/greaterThanOrEqual.mjs";
import { CONDITION_NAME as CONDITION_LESS_THAN } from "./condition/lessThan.mjs";
import { CONDITION_NAME as CONDITION_LESS_THAN_OR_EQUAL } from "./condition/lessThanOrEqual.mjs";
import { CONDITION_NAME as CONDITION_BETWEEN } from "./condition/between.mjs";
import { CONDITION_NAME as CONDITION_NOT_BETWEEN } from "./condition/notBetween.mjs";
import { CONDITION_NAME as CONDITION_BEGINS_WITH } from "./condition/beginsWith.mjs";
import { CONDITION_NAME as CONDITION_ENDS_WITH } from "./condition/endsWith.mjs";
import { CONDITION_NAME as CONDITION_CONTAINS } from "./condition/contains.mjs";
import { CONDITION_NAME as CONDITION_NOT_CONTAINS } from "./condition/notContains.mjs";
import { CONDITION_NAME as CONDITION_DATE_BEFORE } from "./condition/date/before.mjs";
import { CONDITION_NAME as CONDITION_DATE_AFTER } from "./condition/date/after.mjs";
import { CONDITION_NAME as CONDITION_TOMORROW } from "./condition/date/tomorrow.mjs";
import { CONDITION_NAME as CONDITION_TODAY } from "./condition/date/today.mjs";
import { CONDITION_NAME as CONDITION_YESTERDAY } from "./condition/date/yesterday.mjs";
import { CONDITION_NAME as CONDITION_BY_VALUE } from "./condition/byValue.mjs";
import { CONDITION_NAME as CONDITION_TRUE } from "./condition/true.mjs";
import { CONDITION_NAME as CONDITION_FALSE } from "./condition/false.mjs";
import { OPERATION_ID as OPERATION_AND } from "./logicalOperations/conjunction.mjs";
import { OPERATION_ID as OPERATION_OR } from "./logicalOperations/disjunction.mjs";
import { OPERATION_ID as OPERATION_OR_THEN_VARIABLE } from "./logicalOperations/disjunctionWithExtraCondition.mjs";
export { CONDITION_NONE, CONDITION_EMPTY, CONDITION_NOT_EMPTY, CONDITION_EQUAL, CONDITION_NOT_EQUAL, CONDITION_GREATER_THAN, CONDITION_GREATER_THAN_OR_EQUAL, CONDITION_LESS_THAN, CONDITION_LESS_THAN_OR_EQUAL, CONDITION_BETWEEN, CONDITION_NOT_BETWEEN, CONDITION_BEGINS_WITH, CONDITION_ENDS_WITH, CONDITION_CONTAINS, CONDITION_NOT_CONTAINS, CONDITION_DATE_BEFORE, CONDITION_DATE_AFTER, CONDITION_TOMORROW, CONDITION_TODAY, CONDITION_YESTERDAY, CONDITION_BY_VALUE, CONDITION_TRUE, CONDITION_FALSE, OPERATION_AND, OPERATION_OR, OPERATION_OR_THEN_VARIABLE };
export const TYPE_NUMERIC = 'numeric';
export const TYPE_TEXT = 'text';
export const TYPE_DATE = 'date';
/**
 * Default types and order for filter conditions.
 *
 * @type {object}
 */
export const TYPES = {
  [TYPE_NUMERIC]: [CONDITION_NONE, SEPARATOR, CONDITION_EMPTY, CONDITION_NOT_EMPTY, SEPARATOR, CONDITION_EQUAL, CONDITION_NOT_EQUAL, SEPARATOR, CONDITION_GREATER_THAN, CONDITION_GREATER_THAN_OR_EQUAL, CONDITION_LESS_THAN, CONDITION_LESS_THAN_OR_EQUAL, CONDITION_BETWEEN, CONDITION_NOT_BETWEEN],
  [TYPE_TEXT]: [CONDITION_NONE, SEPARATOR, CONDITION_EMPTY, CONDITION_NOT_EMPTY, SEPARATOR, CONDITION_EQUAL, CONDITION_NOT_EQUAL, SEPARATOR, CONDITION_BEGINS_WITH, CONDITION_ENDS_WITH, SEPARATOR, CONDITION_CONTAINS, CONDITION_NOT_CONTAINS],
  [TYPE_DATE]: [CONDITION_NONE, SEPARATOR, CONDITION_EMPTY, CONDITION_NOT_EMPTY, SEPARATOR, CONDITION_EQUAL, CONDITION_NOT_EQUAL, SEPARATOR, CONDITION_DATE_BEFORE, CONDITION_DATE_AFTER, CONDITION_BETWEEN, SEPARATOR, CONDITION_TOMORROW, CONDITION_TODAY, CONDITION_YESTERDAY]
};

/**
 * Get options list for conditional filter by data type (e.q: `'text'`, `'numeric'`, `'date'`).
 *
 * @private
 * @param {string} type The data type.
 * @returns {object}
 */
export default function getOptionsList(type) {
  const items = [];
  let typeName = type;
  if (!TYPES[typeName]) {
    typeName = TYPE_TEXT;
  }
  arrayEach(TYPES[typeName], typeValue => {
    let option;
    if (typeValue === SEPARATOR) {
      option = {
        name: SEPARATOR
      };
    } else {
      option = clone(getConditionDescriptor(typeValue));
    }
    items.push(option);
  });
  return items;
}