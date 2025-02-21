"use strict";

exports.__esModule = true;
exports.getThemeClassName = getThemeClassName;
var _element = require("./dom/element");
/**
 * Retrieves the theme class name from the closest parent element that matches the specified regex pattern.
 *
 * @param {HTMLElement} rootElement - The root element from which to start searching for the theme class.
 * @returns {string} - The theme class name regex.
 */
function getThemeClassName(rootElement) {
  const {
    classNames
  } = (0, _element.findFirstParentWithClass)(rootElement, /ht-theme-[a-zA-Z0-9_-]+/);
  return classNames.pop();
}