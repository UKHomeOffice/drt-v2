"use strict";

exports.__esModule = true;
/**
 * @private
 */
class ClipboardData {
  constructor() {
    this.data = {};
  }
  setData(type, value) {
    this.data[type] = value;
  }
  getData(type) {
    return this.data[type] || void 0;
  }
}
exports.default = ClipboardData;