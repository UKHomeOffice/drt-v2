"use strict";

exports.__esModule = true;
exports.hasValidParameter = hasValidParameter;
exports.isRootInstance = isRootInstance;
exports.registerAsRootInstance = registerAsRootInstance;
const holder = exports.holder = new WeakMap();
const rootInstanceSymbol = exports.rootInstanceSymbol = Symbol('rootInstance');

/**
 * Register an object as a root instance.
 *
 * @param  {object} object An object to associate with root instance flag.
 */
function registerAsRootInstance(object) {
  holder.set(object, true);
}

/**
 * Check if the source of the root indication call is valid.
 *
 * @param  {symbol} rootSymbol A symbol as a source of truth.
 * @returns {boolean}
 */
function hasValidParameter(rootSymbol) {
  return rootSymbol === rootInstanceSymbol;
}

/**
 * Check if passed an object was flagged as a root instance.
 *
 * @param  {object} object An object to check.
 * @returns {boolean}
 */
function isRootInstance(object) {
  return holder.has(object);
}