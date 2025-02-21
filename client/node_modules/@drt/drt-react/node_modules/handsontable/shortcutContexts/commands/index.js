"use strict";

exports.__esModule = true;
exports.createKeyboardShortcutCommandsPool = createKeyboardShortcutCommandsPool;
require("core-js/modules/esnext.iterator.constructor.js");
require("core-js/modules/esnext.iterator.for-each.js");
var _editor = require("./editor");
var _extendCellsSelection = require("./extendCellsSelection");
var _moveCellSelection = require("./moveCellSelection");
var _emptySelectedCells = require("./emptySelectedCells");
var _scrollToFocusedCell = require("./scrollToFocusedCell");
var _selectAllCells = require("./selectAllCells");
var _selectAllCellsAndHeaders = require("./selectAllCellsAndHeaders");
var _populateSelectedCellsData = require("./populateSelectedCellsData");
const allCommands = [...(0, _editor.getAllCommands)(), ...(0, _extendCellsSelection.getAllCommands)(), ...(0, _moveCellSelection.getAllCommands)(), _emptySelectedCells.command, _scrollToFocusedCell.command, _selectAllCells.command, _selectAllCellsAndHeaders.command, _populateSelectedCellsData.command];

/**
 * Prepares and creates an object with all available commands to trigger.
 *
 * @param {Handsontable} hot The Handsontable instance.
 * @returns {object}
 */
function createKeyboardShortcutCommandsPool(hot) {
  const commands = {};
  allCommands.forEach(_ref => {
    let {
      name,
      callback
    } = _ref;
    commands[name] = function () {
      for (var _len = arguments.length, args = new Array(_len), _key = 0; _key < _len; _key++) {
        args[_key] = arguments[_key];
      }
      return callback(hot, ...args);
    };
  });
  return commands;
}