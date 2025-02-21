"use strict";

exports.__esModule = true;
exports.registerActions = registerActions;
require("core-js/modules/esnext.iterator.constructor.js");
require("core-js/modules/esnext.iterator.for-each.js");
var _cellAlignment = require("./cellAlignment");
var _columnMove = require("./columnMove");
var _columnSort = require("./columnSort");
var _createColumn = require("./createColumn");
var _createRow = require("./createRow");
var _dataChange = require("./dataChange");
var _filters = require("./filters");
var _mergeCells = require("./mergeCells");
var _removeColumn = require("./removeColumn");
var _removeRow = require("./removeRow");
var _rowMove = require("./rowMove");
var _unmergeCells = require("./unmergeCells");
/**
 * Register all undo/redo actions.
 *
 * @param {Core} hot The Handsontable instance.
 * @param {UndoRedo} undoRedoPlugin The undoRedo plugin instance.
 */
function registerActions(hot, undoRedoPlugin) {
  [_cellAlignment.CellAlignmentAction, _columnMove.ColumnMoveAction, _columnSort.ColumnSortAction, _createColumn.CreateColumnAction, _createRow.CreateRowAction, _dataChange.DataChangeAction, _filters.FiltersAction, _mergeCells.MergeCellsAction, _removeColumn.RemoveColumnAction, _removeRow.RemoveRowAction, _rowMove.RowMoveAction, _unmergeCells.UnmergeCellsAction].forEach(action => action.startRegisteringEvents(hot, undoRedoPlugin));
}