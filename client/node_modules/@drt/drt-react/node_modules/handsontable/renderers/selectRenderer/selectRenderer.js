"use strict";

exports.__esModule = true;
exports.selectRenderer = selectRenderer;
var _textRenderer = require("../textRenderer");
const RENDERER_TYPE = exports.RENDERER_TYPE = 'select';

/**
 * @private
 * @param {Core} hotInstance The Handsontable instance.
 * @param {HTMLTableCellElement} TD The rendered cell element.
 * @param {number} row The visual row index.
 * @param {number} col The visual column index.
 * @param {number|string} prop The column property (passed when datasource is an array of objects).
 * @param {*} value The rendered value.
 * @param {object} cellProperties The cell meta object (see {@link Core#getCellMeta}).
 */
function selectRenderer(hotInstance, TD, row, col, prop, value, cellProperties) {
  _textRenderer.textRenderer.apply(this, [hotInstance, TD, row, col, prop, value, cellProperties]);
}
selectRenderer.RENDERER_TYPE = RENDERER_TYPE;