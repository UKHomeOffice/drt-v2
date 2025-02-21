"use strict";

exports.__esModule = true;
exports.getRenderedValue = getRenderedValue;
exports.numericRenderer = numericRenderer;
require("core-js/modules/es.array.push.js");
var _numbro = _interopRequireDefault(require("numbro"));
var _textRenderer = require("../textRenderer");
var _number = require("../../helpers/number");
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
const RENDERER_TYPE = exports.RENDERER_TYPE = 'numeric';

/**
 * Get the rendered value.
 *
 * @param {*} value Value to be rendered.
 * @param {CellMeta} cellProperties Cell meta object.
 * @returns {*} Returns the rendered value.
 */
function getRenderedValue(value, cellProperties) {
  if ((0, _number.isNumeric)(value)) {
    const numericFormat = cellProperties.numericFormat;
    const cellCulture = numericFormat && numericFormat.culture || '-';
    const cellFormatPattern = numericFormat && numericFormat.pattern;
    if (typeof cellCulture !== 'undefined' && !_numbro.default.languages()[cellCulture]) {
      const shortTag = cellCulture.replace('-', '');
      const langData = _numbro.default.allLanguages ? _numbro.default.allLanguages[cellCulture] : _numbro.default[shortTag];
      if (langData) {
        _numbro.default.registerLanguage(langData);
      }
    }
    _numbro.default.setLanguage(cellCulture);
    value = (0, _numbro.default)(value).format(cellFormatPattern || '0');
  }
  return value;
}

/**
 * Numeric cell renderer.
 *
 * @private
 * @param {Core} hotInstance The Handsontable instance.
 * @param {HTMLTableCellElement} TD The rendered cell element.
 * @param {number} row The visual row index.
 * @param {number} col The visual column index.
 * @param {number|string} prop The column property (passed when datasource is an array of objects).
 * @param {*} value The rendered value.
 * @param {object} cellProperties The cell meta object (see {@link Core#getCellMeta}).
 */
function numericRenderer(hotInstance, TD, row, col, prop, value, cellProperties) {
  let newValue = value;
  if ((0, _number.isNumeric)(newValue)) {
    const className = cellProperties.className || '';
    const classArr = className.length ? className.split(' ') : [];
    newValue = getRenderedValue(newValue, cellProperties);
    if (classArr.indexOf('htLeft') < 0 && classArr.indexOf('htCenter') < 0 && classArr.indexOf('htRight') < 0 && classArr.indexOf('htJustify') < 0) {
      classArr.push('htRight');
    }
    if (classArr.indexOf('htNumeric') < 0) {
      classArr.push('htNumeric');
    }
    cellProperties.className = classArr.join(' ');
    TD.dir = 'ltr';
  }
  (0, _textRenderer.textRenderer)(hotInstance, TD, row, col, prop, newValue, cellProperties);
}
numericRenderer.RENDERER_TYPE = RENDERER_TYPE;