"use strict";

exports.__esModule = true;
exports.default = jQueryWrapper;
require("core-js/modules/es.error.cause.js");
/**
 * @param {Core} Handsontable The Handsontable instance.
 */
function jQueryWrapper(Handsontable) {
  // eslint-disable-next-line
  const jQuery = typeof window === 'undefined' ? false : window.jQuery;
  if (!jQuery) {
    return;
  }
  jQuery.fn.handsontable = function (action) {
    const $this = this.first(); // Use only first element from list
    let instance = $this.data('handsontable');

    // Init case
    if (typeof action !== 'string') {
      const userSettings = action || {};
      if (instance) {
        instance.updateSettings(userSettings);
      } else {
        instance = new Handsontable.Core($this[0], userSettings);
        $this.data('handsontable', instance);
        instance.init();
      }
      return $this;
    }
    let output;

    // Action case
    if (instance) {
      if (typeof instance[action] !== 'undefined') {
        for (var _len = arguments.length, args = new Array(_len > 1 ? _len - 1 : 0), _key = 1; _key < _len; _key++) {
          args[_key - 1] = arguments[_key];
        }
        output = instance[action].call(instance, ...args);
        if (action === 'destroy') {
          $this.removeData();
        }
      } else {
        throw new Error(`Handsontable do not provide action: ${action}`);
      }
    }
    return output;
  };
}