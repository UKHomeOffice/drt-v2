"use strict";

exports.__esModule = true;
exports.default = copyItem;
var _constants = require("../../../i18n/constants");
/**
 * @param {CopyPaste} copyPastePlugin The plugin instance.
 * @returns {object}
 */
function copyItem(copyPastePlugin) {
  return {
    key: 'copy',
    name() {
      return this.getTranslatedPhrase(_constants.CONTEXTMENU_ITEMS_COPY);
    },
    callback() {
      copyPastePlugin.copyCellsOnly();
    },
    disabled() {
      if (this.countRows() === 0 || this.countCols() === 0) {
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