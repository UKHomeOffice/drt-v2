"use strict";

exports.__esModule = true;
exports.getDefaultLanguageDictionary = getDefaultLanguageDictionary;
exports.getLanguageDictionary = getLanguageDictionary;
exports.getLanguagesDictionaries = getLanguagesDictionaries;
exports.getTranslatedPhrase = getTranslatedPhrase;
exports.getValidLanguageCode = getValidLanguageCode;
exports.hasLanguageDictionary = hasLanguageDictionary;
exports.registerLanguageDictionary = registerLanguageDictionary;
var _object = require("../helpers/object");
var _array = require("./../helpers/array");
var _mixed = require("../helpers/mixed");
var _utils = require("./utils");
var _staticRegister = _interopRequireDefault(require("../utils/staticRegister"));
var _phraseFormatters = require("./phraseFormatters");
var _enUS = _interopRequireDefault(require("./languages/en-US"));
var _dictionaryKeys = _interopRequireWildcard(require("./constants"));
function _getRequireWildcardCache(e) { if ("function" != typeof WeakMap) return null; var r = new WeakMap(), t = new WeakMap(); return (_getRequireWildcardCache = function (e) { return e ? t : r; })(e); }
function _interopRequireWildcard(e, r) { if (!r && e && e.__esModule) return e; if (null === e || "object" != typeof e && "function" != typeof e) return { default: e }; var t = _getRequireWildcardCache(r); if (t && t.has(e)) return t.get(e); var n = { __proto__: null }, a = Object.defineProperty && Object.getOwnPropertyDescriptor; for (var u in e) if ("default" !== u && {}.hasOwnProperty.call(e, u)) { var i = a ? Object.getOwnPropertyDescriptor(e, u) : null; i && (i.get || i.set) ? Object.defineProperty(n, u, i) : n[u] = e[u]; } return n.default = e, t && t.set(e, n), n; }
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
const dictionaryKeys = exports.dictionaryKeys = _dictionaryKeys;
const DEFAULT_LANGUAGE_CODE = exports.DEFAULT_LANGUAGE_CODE = _enUS.default.languageCode;
const {
  register: registerGloballyLanguageDictionary,
  getItem: getGlobalLanguageDictionary,
  hasItem: hasGlobalLanguageDictionary,
  getValues: getGlobalLanguagesDictionaries
} = (0, _staticRegister.default)('languagesDictionaries');

/**
 * Register automatically the default language dictionary.
 */
registerLanguageDictionary(_enUS.default);

/**
 * Register language dictionary for specific language code.
 *
 * @param {string|object} languageCodeOrDictionary Language code for specific language i.e. 'en-US', 'pt-BR', 'de-DE' or object representing dictionary.
 * @param {object} dictionary Dictionary for specific language (optional if first parameter has already dictionary).
 * @returns {object}
 */
function registerLanguageDictionary(languageCodeOrDictionary, dictionary) {
  let languageCode = languageCodeOrDictionary;
  let dictionaryObject = dictionary;

  // Dictionary passed as first argument.
  if ((0, _object.isObject)(languageCodeOrDictionary)) {
    dictionaryObject = languageCodeOrDictionary;
    languageCode = dictionaryObject.languageCode;
  }
  extendLanguageDictionary(languageCode, dictionaryObject);
  registerGloballyLanguageDictionary(languageCode, (0, _object.deepClone)(dictionaryObject));

  // We do not allow user to work with dictionary by reference, it can cause lot of bugs.
  return (0, _object.deepClone)(dictionaryObject);
}

/**
 * Extend handled dictionary by default language dictionary. As result, if any dictionary key isn't defined for specific language, it will be filled with default language value ("dictionary gaps" are supplemented).
 *
 * @private
 * @param {string} languageCode Language code.
 * @param {object} dictionary Dictionary which is extended.
 */
function extendLanguageDictionary(languageCode, dictionary) {
  if (languageCode !== DEFAULT_LANGUAGE_CODE) {
    (0, _utils.extendNotExistingKeys)(dictionary, getGlobalLanguageDictionary(DEFAULT_LANGUAGE_CODE));
  }
}

/**
 * Get language dictionary for specific language code.
 *
 * @param {string} languageCode Language code.
 * @returns {object} Object with constants representing identifiers for translation (as keys) and corresponding translation phrases (as values).
 */
function getLanguageDictionary(languageCode) {
  if (!hasLanguageDictionary(languageCode)) {
    return null;
  }
  return (0, _object.deepClone)(getGlobalLanguageDictionary(languageCode));
}

/**
 *
 * Get if language with specified language code was registered.
 *
 * @param {string} languageCode Language code for specific language i.e. 'en-US', 'pt-BR', 'de-DE'.
 * @returns {boolean}
 */
function hasLanguageDictionary(languageCode) {
  return hasGlobalLanguageDictionary(languageCode);
}

/**
 * Get default language dictionary.
 *
 * @returns {object} Object with constants representing identifiers for translation (as keys) and corresponding translation phrases (as values).
 */
function getDefaultLanguageDictionary() {
  return _enUS.default;
}

/**
 * Get registered language dictionaries.
 *
 * @returns {Array}
 */
function getLanguagesDictionaries() {
  return getGlobalLanguagesDictionaries();
}

/**
 * Get phrase for specified dictionary key.
 *
 * @param {string} languageCode Language code for specific language i.e. 'en-US', 'pt-BR', 'de-DE'.
 * @param {string} dictionaryKey Constant which is dictionary key.
 * @param {*} argumentsForFormatters Arguments which will be handled by formatters.
 *
 * @returns {string}
 */
function getTranslatedPhrase(languageCode, dictionaryKey, argumentsForFormatters) {
  const languageDictionary = getLanguageDictionary(languageCode);
  if (languageDictionary === null) {
    return null;
  }
  const phrasePropositions = languageDictionary[dictionaryKey];
  if ((0, _mixed.isUndefined)(phrasePropositions)) {
    return null;
  }
  const formattedPhrase = getFormattedPhrase(phrasePropositions, argumentsForFormatters);
  if (Array.isArray(formattedPhrase)) {
    return formattedPhrase[0];
  }
  return formattedPhrase;
}

/**
 * Get formatted phrase from phrases propositions for specified dictionary key.
 *
 * @private
 * @param {Array|string} phrasePropositions List of phrase propositions.
 * @param {*} argumentsForFormatters Arguments which will be handled by formatters.
 *
 * @returns {Array|string}
 */
function getFormattedPhrase(phrasePropositions, argumentsForFormatters) {
  let formattedPhrasePropositions = phrasePropositions;
  (0, _array.arrayEach)((0, _phraseFormatters.getPhraseFormatters)(), formatter => {
    formattedPhrasePropositions = formatter(phrasePropositions, argumentsForFormatters);
  });
  return formattedPhrasePropositions;
}

/**
 * Returns valid language code. If the passed language code doesn't exist default one will be used.
 *
 * @param {string} languageCode Language code for specific language i.e. 'en-US', 'pt-BR', 'de-DE'.
 * @returns {string}
 */
function getValidLanguageCode(languageCode) {
  let normalizedLanguageCode = (0, _utils.normalizeLanguageCode)(languageCode);
  if (!hasLanguageDictionary(normalizedLanguageCode)) {
    normalizedLanguageCode = DEFAULT_LANGUAGE_CODE;
    (0, _utils.warnUserAboutLanguageRegistration)(languageCode);
  }
  return normalizedLanguageCode;
}