"use strict";

exports.__esModule = true;
exports.getCondition = getCondition;
exports.getConditionDescriptor = getConditionDescriptor;
exports.registerCondition = registerCondition;
require("core-js/modules/es.error.cause.js");
const conditions = exports.conditions = {};

/**
 * Get condition closure with pre-bound arguments.
 *
 * @param {string} name Condition name.
 * @param {Array} args Condition arguments.
 * @returns {Function}
 */
function getCondition(name, args) {
  if (!conditions[name]) {
    throw Error(`Filter condition "${name}" does not exist.`);
  }
  const {
    condition,
    descriptor
  } = conditions[name];
  let conditionArguments = args;
  if (descriptor.inputValuesDecorator) {
    conditionArguments = descriptor.inputValuesDecorator(conditionArguments);
  }
  return function (dataRow) {
    return condition.apply(dataRow.meta.instance, [].concat([dataRow], [conditionArguments]));
  };
}

/**
 * Get condition object descriptor which defines some additional informations about this condition.
 *
 * @param {string} name Condition name.
 * @returns {object}
 */
function getConditionDescriptor(name) {
  if (!conditions[name]) {
    throw Error(`Filter condition "${name}" does not exist.`);
  }
  return conditions[name].descriptor;
}

/**
 * Condition registerer.
 *
 * @param {string} name Condition name.
 * @param {Function} condition Condition function.
 * @param {object} descriptor Condition descriptor.
 */
function registerCondition(name, condition, descriptor) {
  descriptor.key = name;
  conditions[name] = {
    condition,
    descriptor
  };
}