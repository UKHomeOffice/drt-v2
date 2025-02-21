"use strict";

exports.__esModule = true;
exports.createFocusNavigator = createFocusNavigator;
var _paginator = require("../../../utils/paginator");
var _element = require("../../../helpers/dom/element");
var _multipleSelect = require("../ui/multipleSelect");
/**
 * Creates navigator for switching the focus of the filter's elements.
 *
 * @param {BaseUI[]} elements The elements to paginate to.
 * @returns {Paginator}
 */
function createFocusNavigator(elements) {
  const navigator = (0, _paginator.createPaginator)({
    initialPage: 0,
    size: () => elements.length,
    onItemSelect: (currentIndex, directItemChange) => {
      const element = elements[currentIndex];
      if (element instanceof _multipleSelect.MultipleSelectUI) {
        return directItemChange;
      }
      if (element.element && !(0, _element.isVisible)(element.element)) {
        return false;
      }
      element.focus();
    }
  });
  return navigator;
}