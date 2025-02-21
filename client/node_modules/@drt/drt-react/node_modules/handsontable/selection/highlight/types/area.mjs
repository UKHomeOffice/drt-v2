import { HIGHLIGHT_AREA_TYPE } from "../../../3rdparty/walkontable/src/index.mjs";
import VisualSelection from "../visualSelection.mjs";
/**
 * Creates the new instance of Selection responsible for highlighting area of the selected multiple cells.
 *
 * @param {object} highlightParams A configuration object to create a highlight.
 * @param {object} highlightParams.areaCornerVisible Function to determine if area's corner should be visible.
 * @returns {Selection}
 */
export function createHighlight(_ref) {
  let {
    ...restOptions
  } = _ref;
  return new VisualSelection({
    className: 'highlight',
    ...restOptions,
    selectionType: HIGHLIGHT_AREA_TYPE
  });
}