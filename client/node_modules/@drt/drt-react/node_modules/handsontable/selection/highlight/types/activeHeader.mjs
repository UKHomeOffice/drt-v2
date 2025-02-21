import { HIGHLIGHT_ACTIVE_HEADER_TYPE } from "../../../3rdparty/walkontable/src/index.mjs";
import VisualSelection from "../visualSelection.mjs";
/**
 * Creates the new instance of Selection, responsible for highlighting column or row headers
 * only when the whole column or row is selected.
 * This type of selection can occur multiple times.
 *
 * @param {object} highlightParams A configuration object to create a highlight.
 * @param {string} highlightParams.activeHeaderClassName Highlighted headers' class name.
 * @returns {Selection}
 */
export function createHighlight(_ref) {
  let {
    activeHeaderClassName,
    ...restOptions
  } = _ref;
  return new VisualSelection({
    className: activeHeaderClassName,
    ...restOptions,
    selectionType: HIGHLIGHT_ACTIVE_HEADER_TYPE
  });
}