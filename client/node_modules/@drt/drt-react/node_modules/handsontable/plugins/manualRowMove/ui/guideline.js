"use strict";

exports.__esModule = true;
var _base = _interopRequireDefault(require("./_base"));
var _element = require("../../../helpers/dom/element");
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
const CSS_CLASSNAME = 'ht__manualRowMove--guideline';

/**
 * @private
 * @class GuidelineUI
 */
class GuidelineUI extends _base.default {
  /**
   * Custom className on build process.
   */
  build() {
    super.build();
    (0, _element.addClass)(this._element, CSS_CLASSNAME);
  }
}
var _default = exports.default = GuidelineUI;