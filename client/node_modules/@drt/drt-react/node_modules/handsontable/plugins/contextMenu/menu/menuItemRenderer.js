"use strict";

exports.__esModule = true;
exports.createMenuItemRenderer = createMenuItemRenderer;
var _utils = require("./utils");
var _element = require("../../../helpers/dom/element");
var _a11y = require("../../../helpers/a11y");
/**
 * Creates the menu renderer function.
 *
 * @private
 * @param {Core} mainTableHot The main table Handsontable instance.
 * @returns {Function}
 */
function createMenuItemRenderer(mainTableHot) {
  /**
   * Menu item renderer.
   *
   * @private
   * @param {Core} menuHot The Handsontable instance.
   * @param {HTMLCellElement} TD The rendered cell element.
   * @param {number} row The visual index.
   * @param {number} col The visual index.
   * @param {string} prop The column property if used.
   * @param {string} value The cell value.
   */
  return (menuHot, TD, row, col, prop, value) => {
    const item = menuHot.getSourceDataAtRow(row);
    const wrapper = mainTableHot.rootDocument.createElement('div');
    const itemValue = typeof value === 'function' ? value.call(mainTableHot) : value;
    const ariaLabel = typeof item.ariaLabel === 'function' ? item.ariaLabel.call(mainTableHot) : item.ariaLabel;
    const ariaChecked = typeof item.ariaChecked === 'function' ? item.ariaChecked.call(mainTableHot) : item.ariaChecked;
    (0, _element.empty)(TD);
    (0, _element.addClass)(wrapper, 'htItemWrapper');
    if (mainTableHot.getSettings().ariaTags) {
      const isFocusable = !(0, _utils.isItemDisabled)(item, mainTableHot) && !(0, _utils.isItemSelectionDisabled)(item) && !(0, _utils.isItemSeparator)(item);
      (0, _element.setAttribute)(TD, [...((0, _utils.isItemCheckable)(item) ? [(0, _a11y.A11Y_MENU_ITEM_CHECKBOX)(), (0, _a11y.A11Y_LABEL)(ariaLabel), (0, _a11y.A11Y_CHECKED)(ariaChecked)] : [(0, _a11y.A11Y_MENU_ITEM)(), (0, _a11y.A11Y_LABEL)(itemValue)]), ...(isFocusable ? [(0, _a11y.A11Y_TABINDEX)(-1)] : []), ...((0, _utils.isItemDisabled)(item, mainTableHot) ? [(0, _a11y.A11Y_DISABLED)()] : []), ...((0, _utils.isItemSubMenu)(item) ? [(0, _a11y.A11Y_EXPANDED)(false)] : [])]);
    }
    TD.className = '';
    TD.appendChild(wrapper);
    if ((0, _utils.isItemSeparator)(item)) {
      (0, _element.addClass)(TD, 'htSeparator');
    } else if (typeof item.renderer === 'function') {
      (0, _element.addClass)(TD, 'htCustomMenuRenderer');
      TD.appendChild(item.renderer(menuHot, wrapper, row, col, prop, itemValue));
    } else {
      (0, _element.fastInnerHTML)(wrapper, itemValue);
    }
    if ((0, _utils.isItemDisabled)(item, mainTableHot)) {
      (0, _element.addClass)(TD, 'htDisabled');
    } else if ((0, _utils.isItemSelectionDisabled)(item)) {
      (0, _element.addClass)(TD, 'htSelectionDisabled');
    } else if ((0, _utils.isItemSubMenu)(item)) {
      (0, _element.addClass)(TD, 'htSubmenu');
    }
  };
}