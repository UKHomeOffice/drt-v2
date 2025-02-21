"use strict";

exports.__esModule = true;
exports.registerAllModules = registerAllModules;
var _editors = require("./editors");
exports.registerAllEditors = _editors.registerAllEditors;
var _renderers = require("./renderers");
exports.registerAllRenderers = _renderers.registerAllRenderers;
var _validators = require("./validators");
exports.registerAllValidators = _validators.registerAllValidators;
var _cellTypes = require("./cellTypes");
exports.registerAllCellTypes = _cellTypes.registerAllCellTypes;
var _plugins = require("./plugins");
exports.registerAllPlugins = _plugins.registerAllPlugins;
/* eslint-disable handsontable/restricted-module-imports */
// Since the Handsontable was modularized, importing some submodules is
// restricted. Importing the main entry of the submodule can make the
// "dead" code elimination process more difficult or even impossible.
// The "handsontable/restricted-module-imports" rule is on guard.
// This file exports the functions that allow include packages to
// the Base version of the Handsontable, so that's why the rule is
// disabled here (see more #7506).

/* eslint-enable handsontable/restricted-module-imports */

/**
 * Registers all available Handsontable modules.
 */
function registerAllModules() {
  (0, _editors.registerAllEditors)();
  (0, _renderers.registerAllRenderers)();
  (0, _validators.registerAllValidators)();
  (0, _cellTypes.registerAllCellTypes)();
  (0, _plugins.registerAllPlugins)();
}