"use strict";

exports.__esModule = true;
exports.default = noItemsItem;
exports.KEY = void 0;

var _constants = require("./../../../i18n/constants");

var KEY = 'no_items';
exports.KEY = KEY;

function noItemsItem() {
  return {
    key: KEY,
    name: function name() {
      return this.getTranslatedPhrase(_constants.CONTEXTMENU_ITEMS_NO_ITEMS);
    },
    disabled: true,
    isCommand: false
  };
}