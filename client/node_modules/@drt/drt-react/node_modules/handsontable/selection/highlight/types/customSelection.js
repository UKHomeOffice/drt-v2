"use strict";

exports.__esModule = true;
exports.createHighlight = createHighlight;
var _src = require("../../../3rdparty/walkontable/src");
var _visualSelection = _interopRequireDefault(require("../visualSelection"));
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
/**
 * Creates the new instance of Selection responsible for highlighting currently selected cell.
 * This type of selection can present on the table only one at the time.
 *
 * @param {object} highlightParams A configuration object to create a highlight.
 * @param {object} highlightParams.border Border configuration.
 * @param {object} highlightParams.visualCellRange Function to translate visual to renderable coords.
 * @returns {Selection}
 */
function createHighlight(_ref) {
  let {
    border,
    visualCellRange,
    ...restOptions
  } = _ref;
  return new _visualSelection.default({
    ...border,
    ...restOptions,
    selectionType: _src.HIGHLIGHT_CUSTOM_SELECTION_TYPE
  }, visualCellRange);
}