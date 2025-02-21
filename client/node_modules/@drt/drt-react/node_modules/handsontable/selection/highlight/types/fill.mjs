import { HIGHLIGHT_FILL_TYPE } from "../../../3rdparty/walkontable/src/index.mjs";
import VisualSelection from "../visualSelection.mjs";
/**
 * Creates the new instance of Selection, responsible for highlighting cells which are covered by fill handle
 * functionality. This type of selection can present on the table only one at the time.
 *
 * @param {object} highlightParams A configuration object to create a highlight.
 * @returns {Selection}
 */
export function createHighlight(_ref) {
  let {
    ...restOptions
  } = _ref;
  return new VisualSelection({
    className: 'fill',
    border: {
      width: 1,
      color: '#ff0000'
    },
    ...restOptions,
    selectionType: HIGHLIGHT_FILL_TYPE
  });
}