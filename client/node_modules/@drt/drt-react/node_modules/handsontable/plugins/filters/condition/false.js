"use strict";

exports.__esModule = true;
exports.condition = condition;
var _conditionRegisterer = require("../conditionRegisterer");
const CONDITION_NAME = exports.CONDITION_NAME = 'false';

/**
 * @returns {boolean}
 */
function condition() {
  return false;
}
(0, _conditionRegisterer.registerCondition)(CONDITION_NAME, condition, {
  name: 'False'
});