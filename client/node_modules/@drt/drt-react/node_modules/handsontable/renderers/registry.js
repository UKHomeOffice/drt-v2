"use strict";

exports.__esModule = true;
exports.getRenderer = _getItem;
exports.registerRenderer = _register;
require("core-js/modules/es.error.cause.js");
var _staticRegister = _interopRequireDefault(require("../utils/staticRegister"));
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
const {
  register,
  getItem,
  hasItem,
  getNames,
  getValues
} = (0, _staticRegister.default)('renderers');

/**
 * Retrieve renderer function.
 *
 * @param {string} name Renderer identification.
 * @returns {Function} Returns renderer function.
 */
exports.getRegisteredRenderers = getValues;
exports.getRegisteredRendererNames = getNames;
exports.hasRenderer = hasItem;
function _getItem(name) {
  if (typeof name === 'function') {
    return name;
  }
  if (!hasItem(name)) {
    throw Error(`No registered renderer found under "${name}" name`);
  }
  return getItem(name);
}

/**
 * Register renderer under its alias.
 *
 * @param {string|Function} name Renderer's alias or renderer function with its descriptor.
 * @param {Function} [renderer] Renderer function.
 */
function _register(name, renderer) {
  if (typeof name !== 'string') {
    renderer = name;
    name = renderer.RENDERER_TYPE;
  }
  register(name, renderer);
}