"use strict";

exports.__esModule = true;
exports.createMenuNavigator = createMenuNavigator;
var _paginator = require("../../../utils/paginator");
var _utils = require("./utils");
/**
 * Creates navigator for menus and submenus.
 *
 * @param {Handsontable} hotMenu The Handsontable instance of the menu.
 * @returns {Paginator}
 */
function createMenuNavigator(hotMenu) {
  return (0, _paginator.createPaginator)({
    size: () => hotMenu.countRows(),
    onItemSelect(currentItem, directItemChange) {
      const cell = hotMenu.getCell(currentItem, 0);
      if (!cell || (0, _utils.isSeparator)(cell) || (0, _utils.isDisabled)(cell) || (0, _utils.isSelectionDisabled)(cell)) {
        return false;
      }
      hotMenu.selectCell(currentItem, 0, ...(directItemChange ? [currentItem, 0, false, false] : []));
    },
    onClear() {
      hotMenu.deselectCell();
    }
  });
}