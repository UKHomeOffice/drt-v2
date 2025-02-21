import { CONTEXTMENU_ITEMS_NO_ITEMS } from './../../../i18n/constants';
export var KEY = 'no_items';
export default function noItemsItem() {
  return {
    key: KEY,
    name: function name() {
      return this.getTranslatedPhrase(CONTEXTMENU_ITEMS_NO_ITEMS);
    },
    disabled: true,
    isCommand: false
  };
}