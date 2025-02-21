"use strict";

exports.__esModule = true;
exports.condition = condition;
var _conditionRegisterer = require("../conditionRegisterer");
const CONDITION_NAME = exports.CONDITION_NAME = 'true';

/**
 * @returns {boolean}
 */
function condition() {
  return true;
}
(0, _conditionRegisterer.registerCondition)(CONDITION_NAME, condition, {
  name: 'True'
});