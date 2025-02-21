"use strict";

exports.__esModule = true;
var _clipboardData = _interopRequireDefault(require("./clipboardData"));
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
/**
 * @private
 */
class PasteEvent {
  constructor() {
    this.clipboardData = new _clipboardData.default();
  }
  preventDefault() {}
}
exports.default = PasteEvent;