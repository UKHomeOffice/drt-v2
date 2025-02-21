"use strict";

exports.__esModule = true;
exports.dropdownRenderer = dropdownRenderer;
var _autocompleteRenderer = require("../autocompleteRenderer");
const RENDERER_TYPE = exports.RENDERER_TYPE = 'dropdown';

/**
 * Dropdown renderer.
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
function dropdownRenderer(hotInstance, TD, row, col, prop, value, cellProperties) {
  _autocompleteRenderer.autocompleteRenderer.apply(this, [hotInstance, TD, row, col, prop, value, cellProperties]);
}
dropdownRenderer.RENDERER_TYPE = RENDERER_TYPE;