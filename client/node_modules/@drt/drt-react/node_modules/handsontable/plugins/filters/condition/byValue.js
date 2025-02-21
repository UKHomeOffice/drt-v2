"use strict";

exports.__esModule = true;
exports.condition = condition;
var _conditionRegisterer = require("../conditionRegisterer");
var _utils = require("../utils");
const CONDITION_NAME = exports.CONDITION_NAME = 'by_value';

/**
 * @param {object} dataRow The object which holds and describes the single cell value.
 * @param {Array} inputValues An array of values to compare with.
 * @param {Function} inputValues."0" A function to compare row's data.
 * @returns {boolean}
 */
function condition(dataRow, _ref) {
  let [value] = _ref;
  return value(dataRow.value);
}
(0, _conditionRegisterer.registerCondition)(CONDITION_NAME, condition, {
  name: 'By value',
  inputsCount: 0,
  inputValuesDecorator(_ref2) {
    let [data] = _ref2;
    return [(0, _utils.createArrayAssertion)(data)];
  },
  showOperators: false
});