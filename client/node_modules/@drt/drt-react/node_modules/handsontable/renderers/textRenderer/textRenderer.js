"use strict";

exports.__esModule = true;
exports.textRenderer = textRenderer;
var _baseRenderer = require("../baseRenderer");
var _element = require("../../helpers/dom/element");
var _mixed = require("../../helpers/mixed");
const RENDERER_TYPE = exports.RENDERER_TYPE = 'text';

/**
 * Default text renderer.
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
function textRenderer(hotInstance, TD, row, col, prop, value, cellProperties) {
  _baseRenderer.baseRenderer.apply(this, [hotInstance, TD, row, col, prop, value, cellProperties]);
  let escaped = value;
  if (!escaped && cellProperties.placeholder) {
    escaped = cellProperties.placeholder;
  }
  escaped = (0, _mixed.stringify)(escaped);
  if (cellProperties.trimWhitespace) {
    escaped = escaped.trim();
  }
  if (cellProperties.rendererTemplate) {
    (0, _element.empty)(TD);
    const TEMPLATE = hotInstance.rootDocument.createElement('TEMPLATE');
    TEMPLATE.setAttribute('bind', '{{}}');
    TEMPLATE.innerHTML = cellProperties.rendererTemplate;
    HTMLTemplateElement.decorate(TEMPLATE);
    TEMPLATE.model = hotInstance.getSourceDataAtRow(row);
    TD.appendChild(TEMPLATE);
  } else {
    // this is faster than innerHTML. See: https://github.com/handsontable/handsontable/wiki/JavaScript-&-DOM-performance-tips
    (0, _element.fastInnerText)(TD, escaped);
  }
}
textRenderer.RENDERER_TYPE = RENDERER_TYPE;