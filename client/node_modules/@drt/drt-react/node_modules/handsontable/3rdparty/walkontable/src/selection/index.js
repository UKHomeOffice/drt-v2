"use strict";

require("core-js/modules/esnext.iterator.constructor.js");
require("core-js/modules/esnext.iterator.for-each.js");
exports.__esModule = true;
var _exportNames = {
  SelectionManager: true,
  Selection: true
};
var _selection = _interopRequireDefault(require("./selection"));
exports.Selection = _selection.default;
var _constants = require("./constants");
Object.keys(_constants).forEach(function (key) {
  if (key === "default" || key === "__esModule") return;
  if (Object.prototype.hasOwnProperty.call(_exportNames, key)) return;
  if (key in exports && exports[key] === _constants[key]) return;
  exports[key] = _constants[key];
});
var _utils = require("./border/utils");
Object.keys(_utils).forEach(function (key) {
  if (key === "default" || key === "__esModule") return;
  if (Object.prototype.hasOwnProperty.call(_exportNames, key)) return;
  if (key in exports && exports[key] === _utils[key]) return;
  exports[key] = _utils[key];
});
var _manager = require("./manager");
exports.SelectionManager = _manager.SelectionManager;
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }