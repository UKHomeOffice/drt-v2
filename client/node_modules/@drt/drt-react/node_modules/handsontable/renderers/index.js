"use strict";

exports.__esModule = true;
exports.registerAllRenderers = registerAllRenderers;
var _autocompleteRenderer = require("./autocompleteRenderer");
exports.autocompleteRenderer = _autocompleteRenderer.autocompleteRenderer;
exports.AUTOCOMPLETE_RENDERER = _autocompleteRenderer.RENDERER_TYPE;
var _baseRenderer = require("./baseRenderer");
exports.baseRenderer = _baseRenderer.baseRenderer;
exports.BASE_RENDERER = _baseRenderer.RENDERER_TYPE;
var _dropdownRenderer = require("./dropdownRenderer");
exports.dropdownRenderer = _dropdownRenderer.dropdownRenderer;
exports.DROPDOWN_RENDERER = _dropdownRenderer.RENDERER_TYPE;
var _checkboxRenderer = require("./checkboxRenderer");
exports.checkboxRenderer = _checkboxRenderer.checkboxRenderer;
exports.CHECKBOX_RENDERER = _checkboxRenderer.RENDERER_TYPE;
var _handsontableRenderer = require("./handsontableRenderer");
exports.handsontableRenderer = _handsontableRenderer.handsontableRenderer;
exports.HANDSONTABLE_RENDERER = _handsontableRenderer.RENDERER_TYPE;
var _htmlRenderer = require("./htmlRenderer");
exports.htmlRenderer = _htmlRenderer.htmlRenderer;
exports.HTML_RENDERER = _htmlRenderer.RENDERER_TYPE;
var _numericRenderer = require("./numericRenderer");
exports.numericRenderer = _numericRenderer.numericRenderer;
exports.NUMERIC_RENDERER = _numericRenderer.RENDERER_TYPE;
var _passwordRenderer = require("./passwordRenderer");
exports.passwordRenderer = _passwordRenderer.passwordRenderer;
exports.PASSWORD_RENDERER = _passwordRenderer.RENDERER_TYPE;
var _selectRenderer = require("./selectRenderer");
exports.selectRenderer = _selectRenderer.selectRenderer;
exports.SELECT_RENDERER = _selectRenderer.RENDERER_TYPE;
var _textRenderer = require("./textRenderer");
exports.textRenderer = _textRenderer.textRenderer;
exports.TEXT_RENDERER = _textRenderer.RENDERER_TYPE;
var _timeRenderer = require("./timeRenderer");
exports.timeRenderer = _timeRenderer.timeRenderer;
exports.TIME_RENDERER = _timeRenderer.RENDERER_TYPE;
var _registry = require("./registry");
exports.registerRenderer = _registry.registerRenderer;
exports.getRegisteredRendererNames = _registry.getRegisteredRendererNames;
exports.getRegisteredRenderers = _registry.getRegisteredRenderers;
exports.getRenderer = _registry.getRenderer;
exports.hasRenderer = _registry.hasRenderer;
/**
 * Registers all available renderers.
 */
function registerAllRenderers() {
  (0, _registry.registerRenderer)(_autocompleteRenderer.autocompleteRenderer);
  (0, _registry.registerRenderer)(_baseRenderer.baseRenderer);
  (0, _registry.registerRenderer)(_checkboxRenderer.checkboxRenderer);
  (0, _registry.registerRenderer)(_dropdownRenderer.dropdownRenderer);
  (0, _registry.registerRenderer)(_handsontableRenderer.handsontableRenderer);
  (0, _registry.registerRenderer)(_htmlRenderer.htmlRenderer);
  (0, _registry.registerRenderer)(_numericRenderer.numericRenderer);
  (0, _registry.registerRenderer)(_passwordRenderer.passwordRenderer);
  (0, _registry.registerRenderer)(_selectRenderer.selectRenderer);
  (0, _registry.registerRenderer)(_textRenderer.textRenderer);
  (0, _registry.registerRenderer)(_timeRenderer.timeRenderer);
}