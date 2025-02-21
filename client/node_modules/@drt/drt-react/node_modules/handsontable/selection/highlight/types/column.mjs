import { HIGHLIGHT_COLUMN_TYPE } from "../../../3rdparty/walkontable/src/index.mjs";
import VisualSelection from "../visualSelection.mjs";
/**
 * Creates the new instance of Selection, responsible for highlighting cells in a columns and
 * column headers.
 * This type of selection can occur multiple times.
 *
 * @param {object} highlightParams A configuration object to create a highlight.
 * @param {string} highlightParams.columnClassName Highlighted column' class name.
 * @returns {Selection}
 */
export function createHighlight(_ref) {
  let {
    columnClassName,
    ...restOptions
  } = _ref;
  return new VisualSelection({
    className: columnClassName,
    ...restOptions,
    selectionType: HIGHLIGHT_COLUMN_TYPE
  });
}