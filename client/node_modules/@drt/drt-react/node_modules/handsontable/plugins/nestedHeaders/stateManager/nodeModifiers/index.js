"use strict";

exports.__esModule = true;
exports.triggerNodeModification = triggerNodeModification;
require("core-js/modules/es.error.cause.js");
var _collapse = require("./collapse");
var _expand = require("./expand");
var _hideColumn = require("./hideColumn");
var _showColumn = require("./showColumn");
/**
 * The NodeModifiers module is responsible for the modification of a tree structure
 * in a way to achieve new column headers state.
 */

const availableModifiers = new Map([['collapse', _collapse.collapseNode], ['expand', _expand.expandNode], ['hide-column', _hideColumn.hideColumn], ['show-column', _showColumn.showColumn]]);

/**
 * An entry point for triggering a node modifiers. If the triggered action
 * does not exist the exception is thrown.
 *
 * @param {string} actionName An action name to trigger.
 * @param {TreeNode} nodeToProcess A tree node to process.
 * @param {number} gridColumnIndex The visual column index that comes from the nested headers grid.
 *                                 The index, as opposed to the `columnIndex` in the tree node
 *                                 (which describes the column index of the root node of the header
 *                                 element), describes the index passed from the grid. Hence, the
 *                                 index can be between the column index of the node and its colspan
 *                                 width.
 * @returns {object}
 */
function triggerNodeModification(actionName, nodeToProcess, gridColumnIndex) {
  if (!availableModifiers.has(actionName)) {
    throw new Error(`The node modifier action ("${actionName}") does not exist.`);
  }
  return availableModifiers.get(actionName)(nodeToProcess, gridColumnIndex);
}