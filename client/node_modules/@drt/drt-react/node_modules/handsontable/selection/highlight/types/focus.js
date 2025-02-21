"use strict";

exports.__esModule = true;
exports.createHighlight = createHighlight;
var _src = require("../../../3rdparty/walkontable/src");
var _visualSelection = _interopRequireDefault(require("../visualSelection"));
var _a11y = require("../../../helpers/a11y");
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
/**
 * Creates the new instance of Selection responsible for highlighting currently selected cell. This type of selection
 * can present on the table only one at the time.
 *
 * @param {object} highlightParams A configuration object to create a highlight.
 * @param {Function} highlightParams.cellCornerVisible Function to determine if cell's corner should be visible.
 * @returns {Selection}
 */
function createHighlight(_ref) {
  let {
    cellCornerVisible,
    ...restOptions
  } = _ref;
  return new _visualSelection.default({
    className: 'current',
    headerAttributes: [(0, _a11y.A11Y_SELECTED)()],
    border: {
      width: 2,
      color: '#4b89ff',
      cornerVisible: cellCornerVisible
    },
    ...restOptions,
    selectionType: _src.HIGHLIGHT_FOCUS_TYPE
  });
}