"use strict";

exports.__esModule = true;
exports.installFocusDetector = installFocusDetector;
var _element = require("../../helpers/dom/element");
var _a11y = require("../../helpers/a11y");
/**
 * Installs a focus detector module. The module appends two input elements into the DOM side by side.
 * When the first input is focused, then it means that a user entered to the component using the TAB key
 * from the element above. When the second input is focused, a user enters to the component from
 * the element below the table. Each action, once detected, triggers the specific hook.
 *
 * @param {Handsontable} hot The Handsontable instance.
 * @param {{ onFocusFromTop: Function, onFocusFromBottom: Function }} hooks An object with defined callbacks to call.
 * @returns {{ activate: Function, deactivate: Function }}
 */
function installFocusDetector(hot) {
  let hooks = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : {};
  const rootElement = hot.rootElement;
  const inputTrapTop = createInputElement(hot);
  const inputTrapBottom = createInputElement(hot);
  inputTrapTop.addEventListener('focus', () => hooks === null || hooks === void 0 ? void 0 : hooks.onFocusFromTop());
  inputTrapBottom.addEventListener('focus', () => hooks === null || hooks === void 0 ? void 0 : hooks.onFocusFromBottom());
  rootElement.firstChild.before(inputTrapTop);
  rootElement.lastChild.after(inputTrapBottom);
  return {
    /**
     * Activates the detector by resetting the tabIndex of the input elements.
     */
    activate() {
      hot._registerTimeout(() => {
        inputTrapTop.tabIndex = 0;
        inputTrapBottom.tabIndex = 0;
      }, 10);
    },
    /**
     * Deactivates the detector by setting tabIndex to -1.
     */
    deactivate() {
      hot._registerTimeout(() => {
        inputTrapTop.tabIndex = -1;
        inputTrapBottom.tabIndex = -1;
      }, 10);
    }
  };
}

/**
 * Creates a new HTML input element.
 *
 * @param {Handsontable} hot The Handsontable instance.
 * @returns {HTMLInputElement}
 */
function createInputElement(hot) {
  const rootDocument = hot.rootDocument;
  const input = rootDocument.createElement('input');
  input.type = 'text';
  input.name = '__htFocusCatcher';
  input.classList.add('htFocusCatcher');
  if (hot.getSettings().ariaTags) {
    (0, _element.setAttribute)(input, [(0, _a11y.A11Y_PRESENTATION)(), (0, _a11y.A11Y_HIDDEN)()]);
  }
  return input;
}