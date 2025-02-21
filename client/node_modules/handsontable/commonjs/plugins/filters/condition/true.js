"use strict";

exports.__esModule = true;
exports.condition = condition;
exports.CONDITION_NAME = void 0;

var _conditionRegisterer = require("../conditionRegisterer");

var CONDITION_NAME = 'true';
exports.CONDITION_NAME = CONDITION_NAME;

function condition() {
  return true;
}

(0, _conditionRegisterer.registerCondition)(CONDITION_NAME, condition, {
  name: 'True'
});