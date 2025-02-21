"use strict";

exports.__esModule = true;
exports.default = void 0;

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

/**
 * Base class for the Nested Rows' UI sub-classes.
 *
 * @class
 * @util
 * @private
 */
var BaseUI = function BaseUI(pluginInstance, hotInstance) {
  _classCallCheck(this, BaseUI);

  /**
   * Instance of Handsontable.
   *
   * @type {Core}
   */
  this.hot = hotInstance;
  /**
   * Reference to the main plugin instance.
   */

  this.plugin = pluginInstance;
};

var _default = BaseUI;
exports.default = _default;