"use strict";

exports.__esModule = true;
exports.createHighlight = createHighlight;
var _src = require("../../../3rdparty/walkontable/src");
var _visualSelection = _interopRequireDefault(require("../visualSelection"));
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
/**
 * Creates the new instance of Selection responsible for highlighting area of the selected multiple cells.
 *
 * @param {object} highlightParams A configuration object to create a highlight.
 * @param {object} highlightParams.areaCornerVisible Function to determine if area's corner should be visible.
 * @returns {Selection}
 */
function createHighlight(_ref) {
  let {
    areaCornerVisible,
    ...restOptions
  } = _ref;
  return new _visualSelection.default({
    className: 'area',
    createLayers: true,
    border: {
      width: 1,
      color: '#4b89ff',
      cornerVisible: areaCornerVisible
    },
    ...restOptions,
    selectionType: _src.HIGHLIGHT_AREA_TYPE
  });
}