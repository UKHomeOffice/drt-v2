"use strict";

exports.__esModule = true;
exports.baseRenderer = baseRenderer;
require("core-js/modules/es.array.push.js");
var _element = require("../../helpers/dom/element");
var _a11y = require("../../helpers/a11y");
/**
 * Adds appropriate CSS class to table cell, based on cellProperties.
 */

const RENDERER_TYPE = exports.RENDERER_TYPE = 'base';

/**
 * @param {Core} hotInstance The Handsontable instance.
 * @param {HTMLTableCellElement} TD The rendered cell element.
 * @param {number} row The visual row index.
 * @param {number} col The visual column index.
 * @param {number|string} prop The column property (passed when datasource is an array of objects).
 * @param {*} value The rendered value.
 * @param {object} cellProperties The cell meta object (see {@link Core#getCellMeta}).
 */
function baseRenderer(hotInstance, TD, row, col, prop, value, cellProperties) {
  const ariaEnabled = cellProperties.ariaTags;
  const classesToAdd = [];
  const classesToRemove = [];
  const attributesToRemove = [];
  const attributesToAdd = [];
  if (cellProperties.className) {
    (0, _element.addClass)(TD, cellProperties.className);
  }
  if (cellProperties.readOnly) {
    classesToAdd.push(cellProperties.readOnlyCellClassName);
    if (ariaEnabled) {
      attributesToAdd.push((0, _a11y.A11Y_READONLY)());
    }
  } else if (ariaEnabled) {
    attributesToRemove.push((0, _a11y.A11Y_READONLY)()[0]);
  }
  if (cellProperties.valid === false && cellProperties.invalidCellClassName) {
    classesToAdd.push(cellProperties.invalidCellClassName);
    if (ariaEnabled) {
      attributesToAdd.push((0, _a11y.A11Y_INVALID)());
    }
  } else {
    classesToRemove.push(cellProperties.invalidCellClassName);
    if (ariaEnabled) {
      attributesToRemove.push((0, _a11y.A11Y_INVALID)()[0]);
    }
  }
  if (cellProperties.wordWrap === false && cellProperties.noWordWrapClassName) {
    classesToAdd.push(cellProperties.noWordWrapClassName);
  }
  if (!value && cellProperties.placeholder) {
    classesToAdd.push(cellProperties.placeholderCellClassName);
  }
  (0, _element.removeClass)(TD, classesToRemove);
  (0, _element.addClass)(TD, classesToAdd);
  (0, _element.removeAttribute)(TD, attributesToRemove);
  (0, _element.setAttribute)(TD, attributesToAdd);
}
baseRenderer.RENDERER_TYPE = RENDERER_TYPE;