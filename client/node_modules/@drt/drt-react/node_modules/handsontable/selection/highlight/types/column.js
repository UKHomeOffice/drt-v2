"use strict";

exports.__esModule = true;
exports.createHighlight = createHighlight;
var _src = require("../../../3rdparty/walkontable/src");
var _visualSelection = _interopRequireDefault(require("../visualSelection"));
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
/**
 * Creates the new instance of Selection, responsible for highlighting cells in a columns and
 * column headers.
 * This type of selection can occur multiple times.
 *
 * @param {object} highlightParams A configuration object to create a highlight.
 * @param {string} highlightParams.columnClassName Highlighted column' class name.
 * @returns {Selection}
 */
function createHighlight(_ref) {
  let {
    columnClassName,
    ...restOptions
  } = _ref;
  return new _visualSelection.default({
    className: columnClassName,
    ...restOptions,
    selectionType: _src.HIGHLIGHT_COLUMN_TYPE
  });
}