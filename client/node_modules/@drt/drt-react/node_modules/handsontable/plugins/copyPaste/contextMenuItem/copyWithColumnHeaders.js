"use strict";

exports.__esModule = true;
exports.default = copyWithColumnHeadersItem;
var _constants = require("../../../i18n/constants");
var _number = require("../../../helpers/number");
/**
 * @param {CopyPaste} copyPastePlugin The plugin instance.
 * @returns {object}
 */
function copyWithColumnHeadersItem(copyPastePlugin) {
  return {
    key: 'copy_with_column_headers',
    name() {
      const selectedRange = this.getSelectedRangeLast();
      const nounForm = selectedRange ? (0, _number.clamp)(selectedRange.getWidth() - 1, 0, 1) : 0;
      return this.getTranslatedPhrase(_constants.CONTEXTMENU_ITEMS_COPY_WITH_COLUMN_HEADERS, nounForm);
    },
    callback() {
      copyPastePlugin.copyWithColumnHeaders();
    },
    disabled() {
      if (!this.hasColHeaders()) {
        return true;
      }
      const range = this.getSelectedRangeLast();
      if (!range) {
        return true;
      }
      if (range.isSingleHeader()) {
        return true;
      }
      const selected = this.getSelected();

      // Disable for no selection or for non-contiguous selection.
      if (!selected || selected.length > 1) {
        return true;
      }
      return false;
    },
    hidden: false
  };
}