"use strict";

exports.__esModule = true;
exports.default = noItemsItem;
var _constants = require("../../../i18n/constants");
const KEY = exports.KEY = 'no_items';

/**
 * @returns {object}
 */
function noItemsItem() {
  return {
    key: KEY,
    name() {
      return this.getTranslatedPhrase(_constants.CONTEXTMENU_ITEMS_NO_ITEMS);
    },
    disabled: true,
    isCommand: false
  };
}