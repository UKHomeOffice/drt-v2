"use strict";

require("core-js/modules/es.function.name");

exports.__esModule = true;
exports.getOperationFunc = getOperationFunc;
exports.getOperationName = getOperationName;
exports.registerOperation = registerOperation;
exports.operations = void 0;
var operations = {};
/**
 * Get operation closure with pre-bound arguments.
 *
 * @param {String} id Operator `id`.
 * @returns {Function}
 */

exports.operations = operations;

function getOperationFunc(id) {
  if (!operations[id]) {
    throw Error("Operation with id \"".concat(id, "\" does not exist."));
  }

  var func = operations[id].func;
  return function (conditions, value) {
    return func(conditions, value);
  };
}
/**
 * Return name of operation which is displayed inside UI component, basing on it's `id`.
 *
 * @param {String} id `Id` of operation.
 */


function getOperationName(id) {
  return operations[id].name;
}
/**
 * Operator registerer.
 *
 * @param {String} id Operation `id`.
 * @param {String} name Operation name which is displayed inside UI component.
 * @param {Function} func Operation function.
 */


function registerOperation(id, name, func) {
  operations[id] = {
    name: name,
    func: func
  };
}