"use strict";

exports.__esModule = true;
exports.collapseNode = collapseNode;
require("core-js/modules/es.set.difference.v2.js");
require("core-js/modules/es.set.intersection.v2.js");
require("core-js/modules/es.set.is-disjoint-from.v2.js");
require("core-js/modules/es.set.is-subset-of.v2.js");
require("core-js/modules/es.set.is-superset-of.v2.js");
require("core-js/modules/es.set.symmetric-difference.v2.js");
require("core-js/modules/es.set.union.v2.js");
var _array = require("../../../../helpers/array");
var _expand = require("./expand");
var _tree = require("./utils/tree");
/**
 * Collapsing a node is a process where the processing node is collapsed
 * to the colspan width of the first child. All node children, except the
 * first one, are hidden. To prevent losing a current state of node children
 * on the right, all nodes are cloned (and restored while expanding), and
 * only then original nodes are modified (hidden in this case).
 *
 * @param {TreeNode} nodeToProcess A tree node to process.
 * @returns {object} Returns an object with properties:
 *                    - rollbackModification: The function that rollbacks
 *                      the tree to the previous state.
 *                    - affectedColumns: The list of the visual column
 *                      indexes which are affected. That list is passed
 *                      to the hiddens column logic.
 *                    - colspanCompensation: The number of colspan by
 *                      which the processed node colspan was reduced.
 */
function collapseNode(nodeToProcess) {
  var _getFirstChildPropert;
  const {
    data: nodeData,
    childs: nodeChilds
  } = nodeToProcess;
  if (nodeData.isCollapsed || nodeData.isHidden || nodeData.origColspan <= 1) {
    return {
      rollbackModification: () => {},
      affectedColumns: [],
      colspanCompensation: 0
    };
  }
  const isNodeReflected = (0, _tree.isNodeReflectsFirstChildColspan)(nodeToProcess);
  if (isNodeReflected) {
    return collapseNode(nodeChilds[0]);
  }
  nodeData.isCollapsed = true;
  const allLeavesExceptMostLeft = nodeChilds.slice(1);
  const affectedColumns = new Set();
  if (allLeavesExceptMostLeft.length > 0) {
    (0, _array.arrayEach)(allLeavesExceptMostLeft, node => {
      (0, _tree.traverseHiddenNodeColumnIndexes)(node, gridColumnIndex => {
        affectedColumns.add(gridColumnIndex);
      });

      // Clone the tree to preserve original tree state after header expanding.
      node.data.clonedTree = node.cloneTree();

      // Hide all leaves except the first leaf on the left (on headers context hide all
      // headers on the right).
      node.walkDown(_ref => {
        let {
          data
        } = _ref;
        data.isHidden = true;
      });
    });
  } else {
    const {
      origColspan,
      columnIndex
    } = nodeData;

    // Add column to "affected" started from 1. The header without children can not be
    // collapsed so the first have to be visible (untouched).
    for (let i = 1; i < origColspan; i++) {
      const gridColumnIndex = columnIndex + i;
      affectedColumns.add(gridColumnIndex);
    }
  }

  // Calculate by how many colspan it needs to reduce the headings to match them to
  // the first child colspan width.
  const colspanCompensation = nodeData.colspan - ((_getFirstChildPropert = (0, _tree.getFirstChildProperty)(nodeToProcess, 'colspan')) !== null && _getFirstChildPropert !== void 0 ? _getFirstChildPropert : 1);
  nodeToProcess.walkUp(node => {
    const {
      data
    } = node;
    data.colspan -= colspanCompensation;
    if (data.colspan <= 1) {
      data.colspan = 1;
      data.isCollapsed = true;
    } else if ((0, _tree.isNodeReflectsFirstChildColspan)(node)) {
      data.isCollapsed = (0, _tree.getFirstChildProperty)(node, 'isCollapsed');
    }
  });
  return {
    rollbackModification: () => (0, _expand.expandNode)(nodeToProcess),
    affectedColumns: Array.from(affectedColumns),
    colspanCompensation
  };
}