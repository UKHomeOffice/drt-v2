"use strict";

exports.__esModule = true;
exports.getAllCommands = getAllCommands;
var _closeAndSave = require("./closeAndSave");
var _closeAndSaveByArrowKeys = require("./closeAndSaveByArrowKeys");
var _closeAndSaveByEnter = require("./closeAndSaveByEnter");
var _closeWithoutSaving = require("./closeWithoutSaving");
var _fastOpen = require("./fastOpen");
var _open = require("./open");
/**
 * Returns complete list of the shortcut commands for the cells editing feature.
 *
 * @returns {Function[]}
 */
function getAllCommands() {
  return [_closeAndSave.command, _closeAndSaveByArrowKeys.command, _closeAndSaveByEnter.command, _closeWithoutSaving.command, _fastOpen.command, _open.command];
}