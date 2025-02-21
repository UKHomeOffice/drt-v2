"use strict";

exports.__esModule = true;
exports.default = substituteVariables;
require("core-js/modules/esnext.iterator.map.js");
var _string = require("./../../helpers/string");
/**
 * Try to substitute variable inside phrase propositions.
 *
 * @param {Array} phrasePropositions List of phrases propositions.
 * @param {object} zippedVariablesAndValues Object containing variables and corresponding values.
 *
 * @returns {string} Phrases with substituted variables if it's possible, list of unchanged phrase propositions otherwise.
 */
function substituteVariables(phrasePropositions, zippedVariablesAndValues) {
  if (Array.isArray(phrasePropositions)) {
    return phrasePropositions.map(phraseProposition => substituteVariables(phraseProposition, zippedVariablesAndValues));
  }
  return (0, _string.substitute)(phrasePropositions, zippedVariablesAndValues);
}