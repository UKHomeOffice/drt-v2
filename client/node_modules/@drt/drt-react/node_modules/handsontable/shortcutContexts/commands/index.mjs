import "core-js/modules/esnext.iterator.constructor.js";
import "core-js/modules/esnext.iterator.for-each.js";
import { getAllCommands as getAllEditorCommands } from "./editor/index.mjs";
import { getAllCommands as getAllSelectionExtendCommands } from "./extendCellsSelection/index.mjs";
import { getAllCommands as getAllSelectionMoveCommands } from "./moveCellSelection/index.mjs";
import { command as emptySelectedCells } from "./emptySelectedCells.mjs";
import { command as scrollToFocusedCell } from "./scrollToFocusedCell.mjs";
import { command as selectAllCells } from "./selectAllCells.mjs";
import { command as selectAllCellsAndHeaders } from "./selectAllCellsAndHeaders.mjs";
import { command as populateSelectedCellsData } from "./populateSelectedCellsData.mjs";
const allCommands = [...getAllEditorCommands(), ...getAllSelectionExtendCommands(), ...getAllSelectionMoveCommands(), emptySelectedCells, scrollToFocusedCell, selectAllCells, selectAllCellsAndHeaders, populateSelectedCellsData];

/**
 * Prepares and creates an object with all available commands to trigger.
 *
 * @param {Handsontable} hot The Handsontable instance.
 * @returns {object}
 */
export function createKeyboardShortcutCommandsPool(hot) {
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