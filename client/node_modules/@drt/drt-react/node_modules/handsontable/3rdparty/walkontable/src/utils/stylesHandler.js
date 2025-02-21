"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
require("core-js/modules/esnext.iterator.constructor.js");
require("core-js/modules/esnext.iterator.for-each.js");
var _element = require("../../../../helpers/dom/element");
var _console = require("../../../../helpers/console");
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
const CLASSIC_THEME_DEFAULT_HEIGHT = 23;

/**
 * Handles the theme-related style operations.
 */
var _themeName = /*#__PURE__*/new WeakMap();
var _rootElement = /*#__PURE__*/new WeakMap();
var _rootComputedStyle = /*#__PURE__*/new WeakMap();
var _rootDocument = /*#__PURE__*/new WeakMap();
var _isClassicTheme = /*#__PURE__*/new WeakMap();
var _cssVars = /*#__PURE__*/new WeakMap();
var _computedStyles = /*#__PURE__*/new WeakMap();
var _StylesHandler_brand = /*#__PURE__*/new WeakSet();
class StylesHandler {
  /**
   * Initializes a new instance of the `StylesHandler` class.
   *
   * @param {object} domBindings - The DOM bindings for the instance.
   */
  constructor(domBindings) {
    /**
     * Calculates the row height based on the current theme and CSS variables.
     *
     * @returns {number|null} The calculated row height, or `null` if any required CSS variable is not found.
     */
    _classPrivateMethodInitSpec(this, _StylesHandler_brand);
    /**
     * The name of the theme.
     *
     * @type {string|undefined}
     */
    _classPrivateFieldInitSpec(this, _themeName, void 0);
    /**
     * The instance's root element.
     *
     * @type {HTMLElement}
     */
    _classPrivateFieldInitSpec(this, _rootElement, void 0);
    /**
     * The computed style of the root element.
     *
     * @type {CSSStyleDeclaration}
     * @private
     */
    _classPrivateFieldInitSpec(this, _rootComputedStyle, void 0);
    /**
     * The root document of the instance.
     *
     * @type {Document}
     * @private
     */
    _classPrivateFieldInitSpec(this, _rootDocument, void 0);
    /**
     * `true` if the classic theme is enabled, `false` otherwise.
     *
     * @type {boolean}
     */
    _classPrivateFieldInitSpec(this, _isClassicTheme, true);
    /**
     * An object to store CSS variable values.
     *
     * @type {object}
     * @private
     */
    _classPrivateFieldInitSpec(this, _cssVars, {});
    /**
     * Stores the computed styles for various elements.
     *
     * @type {object} - An object containing the computed styles if a nested structore of `element: { [element type]: {property: value} }`.
     * @private
     */
    _classPrivateFieldInitSpec(this, _computedStyles, {});
    _classPrivateFieldSet(_rootElement, this, domBindings.rootTable.parentElement.parentElement);
    _classPrivateFieldSet(_rootDocument, this, domBindings.rootDocument);
  }

  /**
   * Gets the value indicating whether the classic theme is enabled.
   *
   * @returns {boolean} `true` if the classic theme is enabled, `false` otherwise.
   */
  isClassicTheme() {
    return _classPrivateFieldGet(_isClassicTheme, this);
  }

  /**
   * Retrieves the value of a specified CSS variable.
   *
   * @param {string} variableName - The name of the CSS variable to retrieve.
   * @returns {number|null|undefined} The value of the specified CSS variable, or `undefined` if not found.
   */
  getCSSVariableValue(variableName) {
    var _assertClassBrand$cal;
    if (_classPrivateFieldGet(_isClassicTheme, this)) {
      return null;
    }
    if (_classPrivateFieldGet(_cssVars, this)[`--ht-${variableName}`]) {
      return _classPrivateFieldGet(_cssVars, this)[`--ht-${variableName}`];
    }
    const acquiredValue = (_assertClassBrand$cal = _assertClassBrand(_StylesHandler_brand, this, _getParsedNumericCSSValue).call(this, `--ht-${variableName}`)) !== null && _assertClassBrand$cal !== void 0 ? _assertClassBrand$cal : _assertClassBrand(_StylesHandler_brand, this, _getCSSValue).call(this, `--ht-${variableName}`);
    if (acquiredValue !== null) {
      _classPrivateFieldGet(_cssVars, this)[`--ht-${variableName}`] = acquiredValue;
      return acquiredValue;
    }
  }

  /**
   * Retrieves the computed style value for a specified CSS property of a `td` element.
   *
   * @param {string} cssProperty - The CSS property to retrieve the value for.
   * @returns {number|string|undefined} The value of the specified CSS property, or `undefined` if not found.
   */
  getStyleForTD(cssProperty) {
    var _classPrivateFieldGet2;
    return (_classPrivateFieldGet2 = _classPrivateFieldGet(_computedStyles, this)) === null || _classPrivateFieldGet2 === void 0 ? void 0 : _classPrivateFieldGet2.td[cssProperty];
  }

  /**
   * Calculates the row height based on the current theme and CSS variables.
   *
   * @returns {number} The calculated row height.
   */
  getDefaultRowHeight() {
    if (_classPrivateFieldGet(_isClassicTheme, this)) {
      return CLASSIC_THEME_DEFAULT_HEIGHT;
    }
    const calculatedRowHeight = _assertClassBrand(_StylesHandler_brand, this, _calculateRowHeight).call(this);
    if (!calculatedRowHeight && (0, _element.hasClass)(_classPrivateFieldGet(_rootElement, this), 'ht-wrapper')) {
      (0, _console.warn)(`The "${_classPrivateFieldGet(_themeName, this)}" theme is enabled, but its stylesheets are missing or not imported correctly. \
Import the correct CSS files in order to use that theme.`);
      _classPrivateFieldSet(_isClassicTheme, this, true);
      this.useTheme();
      return CLASSIC_THEME_DEFAULT_HEIGHT;
    }
    return calculatedRowHeight;
  }

  /**
   * Checks if the cells are using the `border-box` box-sizing model.
   *
   * @returns {boolean}
   */
  areCellsBorderBox() {
    return this.getStyleForTD('box-sizing') === 'border-box';
  }

  /**
   * Applies the specified theme to the instance.
   *
   * @param {string|undefined|boolean} [themeName] - The name of the theme to apply.
   */
  useTheme(themeName) {
    if (!themeName) {
      _assertClassBrand(_StylesHandler_brand, this, _cacheStylesheetValues).call(this);
      _classPrivateFieldSet(_isClassicTheme, this, true);
      _classPrivateFieldSet(_themeName, this, themeName || undefined);
      return;
    }
    if (themeName && themeName !== _classPrivateFieldGet(_themeName, this)) {
      if (_classPrivateFieldGet(_themeName, this)) {
        _assertClassBrand(_StylesHandler_brand, this, _clearCachedValues).call(this);
      }
      _classPrivateFieldSet(_themeName, this, themeName);
      _classPrivateFieldSet(_isClassicTheme, this, false);
      _assertClassBrand(_StylesHandler_brand, this, _applyClassNames).call(this);
      _assertClassBrand(_StylesHandler_brand, this, _cacheStylesheetValues).call(this);
    }
  }

  /**
   * Gets the name of the theme.
   *
   * @returns {string|undefined}
   */
  getThemeName() {
    return _classPrivateFieldGet(_themeName, this);
  }

  /**
   * Removes the theme-related class names from the root element.
   */
  removeClassNames() {
    if ((0, _element.hasClass)(_classPrivateFieldGet(_rootElement, this), _classPrivateFieldGet(_themeName, this))) {
      (0, _element.removeClass)(_classPrivateFieldGet(_rootElement, this), _classPrivateFieldGet(_themeName, this));
    }
  }
}
exports.StylesHandler = StylesHandler;
function _calculateRowHeight() {
  const lineHeightVarValue = this.getCSSVariableValue('line-height');
  const verticalPaddingVarValue = this.getCSSVariableValue('cell-vertical-padding');
  const bottomBorderWidth = Math.ceil(parseFloat(this.getStyleForTD('border-bottom-width')));
  if (lineHeightVarValue === null || verticalPaddingVarValue === null || isNaN(bottomBorderWidth)) {
    return null;
  }
  return lineHeightVarValue + 2 * verticalPaddingVarValue + bottomBorderWidth;
}
/**
 * Applies the necessary class names to the root element.
 */
function _applyClassNames() {
  (0, _element.removeClass)(_classPrivateFieldGet(_rootElement, this), /ht-theme-.*/g);
  (0, _element.addClass)(_classPrivateFieldGet(_rootElement, this), _classPrivateFieldGet(_themeName, this));
}
/**
 * Caches the computed style values for the root element and `td` element.
 */
function _cacheStylesheetValues() {
  if (!this.isClassicTheme()) {
    _classPrivateFieldSet(_rootComputedStyle, this, getComputedStyle(_classPrivateFieldGet(_rootElement, this)));
  }
  const stylesForTD = _assertClassBrand(_StylesHandler_brand, this, _getStylesForTD).call(this, ['box-sizing', 'border-bottom-width']);
  _classPrivateFieldGet(_computedStyles, this).td = {
    ..._classPrivateFieldGet(_computedStyles, this).td,
    ...{
      'box-sizing': stylesForTD['box-sizing'],
      'border-bottom-width': stylesForTD['border-bottom-width']
    }
  };
}
/**
 * Retrieves and processes the computed styles for a `td` element.
 *
 * This method creates a temporary table structure, appends it to the root element,
 * retrieves the computed styles for the `td` element, and then removes the table
 * from the DOM. The computed styles are passed to the provided callback function.
 *
 * @param {Array} cssProps - An array of CSS properties to retrieve.
 * @returns {object} An object containing the requested computed styles for the `td` element.
 * @private
 */
function _getStylesForTD(cssProps) {
  const rootDocument = _classPrivateFieldGet(_rootDocument, this);
  const rootElement = _classPrivateFieldGet(_rootElement, this);
  const table = rootDocument.createElement('table');
  const tbody = rootDocument.createElement('tbody');
  const tr = rootDocument.createElement('tr');
  // This needs not to be the first row in order to get "regular" vaules.
  const tr2 = rootDocument.createElement('tr');
  const td = rootDocument.createElement('td');
  tr2.appendChild(td);
  tbody.appendChild(tr);
  tbody.appendChild(tr2);
  table.appendChild(tbody);
  rootElement.appendChild(table);
  const computedStyle = getComputedStyle(td);
  const returnObject = {};
  cssProps.forEach(prop => {
    returnObject[prop] = computedStyle.getPropertyValue(prop);
  });
  rootElement.removeChild(table);
  return returnObject;
}
/**
 * Parses the numeric value of a specified CSS property from the root element's computed style.
 *
 * @param {string} property - The CSS property to retrieve and parse.
 * @returns {number|null} The parsed value of the CSS property or `null` if non-existent.
 */
function _getParsedNumericCSSValue(property) {
  const parsedValue = Math.ceil(parseFloat(_assertClassBrand(_StylesHandler_brand, this, _getCSSValue).call(this, property)));
  return Number.isNaN(parsedValue) ? null : parsedValue;
}
/**
 * Retrieves the non-numeric value of a specified CSS property from the root element's computed style.
 *
 * @param {string} property - The CSS property to retrieve.
 * @returns {string|null} The value of the specified CSS property or `null` if non-existent.
 */
function _getCSSValue(property) {
  const acquiredValue = _classPrivateFieldGet(_rootComputedStyle, this).getPropertyValue(property);
  return acquiredValue === '' ? null : acquiredValue;
}
/**
 * Clears the cached values.
 */
function _clearCachedValues() {
  _classPrivateFieldSet(_computedStyles, this, {});
  _classPrivateFieldSet(_cssVars, this, {});
  _classPrivateFieldSet(_isClassicTheme, this, true);
}