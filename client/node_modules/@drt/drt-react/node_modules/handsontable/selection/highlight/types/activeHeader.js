"use strict";

exports.__esModule = true;
exports.createHighlight = createHighlight;
var _src = require("../../../3rdparty/walkontable/src");
var _visualSelection = _interopRequireDefault(require("../visualSelection"));
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
/**
 * Creates the new instance of Selection, responsible for highlighting column or row headers
 * only when the whole column or row is selected.
 * This type of selection can occur multiple times.
 *
 * @param {object} highlightParams A configuration object to create a highlight.
 * @param {string} highlightParams.activeHeaderClassName Highlighted headers' class name.
 * @returns {Selection}
 */
function createHighlight(_ref) {
  let {
    activeHeaderClassName,
    ...restOptions
  } = _ref;
  return new _visualSelection.default({
    className: activeHeaderClassName,
    ...restOptions,
    selectionType: _src.HIGHLIGHT_ACTIVE_HEADER_TYPE
  });
}