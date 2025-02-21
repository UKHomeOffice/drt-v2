import "core-js/modules/esnext.iterator.constructor.js";
import "core-js/modules/esnext.iterator.for-each.js";
import { CellAlignmentAction } from "./cellAlignment.mjs";
import { ColumnMoveAction } from "./columnMove.mjs";
import { ColumnSortAction } from "./columnSort.mjs";
import { CreateColumnAction } from "./createColumn.mjs";
import { CreateRowAction } from "./createRow.mjs";
import { DataChangeAction } from "./dataChange.mjs";
import { FiltersAction } from "./filters.mjs";
import { MergeCellsAction } from "./mergeCells.mjs";
import { RemoveColumnAction } from "./removeColumn.mjs";
import { RemoveRowAction } from "./removeRow.mjs";
import { RowMoveAction } from "./rowMove.mjs";
import { UnmergeCellsAction } from "./unmergeCells.mjs";
/**
 * Register all undo/redo actions.
 *
 * @param {Core} hot The Handsontable instance.
 * @param {UndoRedo} undoRedoPlugin The undoRedo plugin instance.
 */
export function registerActions(hot, undoRedoPlugin) {
  [CellAlignmentAction, ColumnMoveAction, ColumnSortAction, CreateColumnAction, CreateRowAction, DataChangeAction, FiltersAction, MergeCellsAction, RemoveColumnAction, RemoveRowAction, RowMoveAction, UnmergeCellsAction].forEach(action => action.startRegisteringEvents(hot, undoRedoPlugin));
}