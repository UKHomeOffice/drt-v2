"use strict";

exports.__esModule = true;
exports.getPhraseFormatters = exports.getAll = getAll;
exports.registerPhraseFormatter = exports.register = register;
var _staticRegister = _interopRequireDefault(require("./../../utils/staticRegister"));
var _pluralize = _interopRequireDefault(require("./pluralize"));
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
const {
  register: registerGloballyPhraseFormatter,
  getValues: getGlobalPhraseFormatters
} = (0, _staticRegister.default)('phraseFormatters');

/**
 * Register phrase formatter.
 *
 * @param {string} name Name of formatter.
 * @param {Function} formatterFn Function which will be applied on phrase propositions. It will transform them if it's possible.
 */
function register(name, formatterFn) {
  registerGloballyPhraseFormatter(name, formatterFn);
}

/**
 * Get all registered previously formatters.
 *
 * @returns {Array}
 */
function getAll() {
  return getGlobalPhraseFormatters();
}
register('pluralize', _pluralize.default);