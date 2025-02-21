import "core-js/modules/es.symbol";
import "core-js/modules/es.symbol.description";
import "core-js/modules/es.symbol.iterator";
import "core-js/modules/es.array.filter";
import "core-js/modules/es.array.find";
import "core-js/modules/es.array.from";
import "core-js/modules/es.array.includes";
import "core-js/modules/es.array.index-of";
import "core-js/modules/es.array.iterator";
import "core-js/modules/es.array.slice";
import "core-js/modules/es.array.sort";
import "core-js/modules/es.array.splice";
import "core-js/modules/es.object.get-prototype-of";
import "core-js/modules/es.object.set-prototype-of";
import "core-js/modules/es.object.to-string";
import "core-js/modules/es.regexp.to-string";
import "core-js/modules/es.string.iterator";
import "core-js/modules/web.dom-collections.iterator";

function _typeof(obj) { if (typeof Symbol === "function" && typeof Symbol.iterator === "symbol") { _typeof = function _typeof(obj) { return typeof obj; }; } else { _typeof = function _typeof(obj) { return obj && typeof Symbol === "function" && obj.constructor === Symbol && obj !== Symbol.prototype ? "symbol" : typeof obj; }; } return _typeof(obj); }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

function _possibleConstructorReturn(self, call) { if (call && (_typeof(call) === "object" || typeof call === "function")) { return call; } return _assertThisInitialized(self); }

function _assertThisInitialized(self) { if (self === void 0) { throw new ReferenceError("this hasn't been initialised - super() hasn't been called"); } return self; }

function _getPrototypeOf(o) { _getPrototypeOf = Object.setPrototypeOf ? Object.getPrototypeOf : function _getPrototypeOf(o) { return o.__proto__ || Object.getPrototypeOf(o); }; return _getPrototypeOf(o); }

function _inherits(subClass, superClass) { if (typeof superClass !== "function" && superClass !== null) { throw new TypeError("Super expression must either be null or a function"); } subClass.prototype = Object.create(superClass && superClass.prototype, { constructor: { value: subClass, writable: true, configurable: true } }); if (superClass) _setPrototypeOf(subClass, superClass); }

function _setPrototypeOf(o, p) { _setPrototypeOf = Object.setPrototypeOf || function _setPrototypeOf(o, p) { o.__proto__ = p; return o; }; return _setPrototypeOf(o, p); }

function _toConsumableArray(arr) { return _arrayWithoutHoles(arr) || _iterableToArray(arr) || _nonIterableSpread(); }

function _nonIterableSpread() { throw new TypeError("Invalid attempt to spread non-iterable instance"); }

function _iterableToArray(iter) { if (Symbol.iterator in Object(iter) || Object.prototype.toString.call(iter) === "[object Arguments]") return Array.from(iter); }

function _arrayWithoutHoles(arr) { if (Array.isArray(arr)) { for (var i = 0, arr2 = new Array(arr.length); i < arr.length; i++) { arr2[i] = arr[i]; } return arr2; } }

function _slicedToArray(arr, i) { return _arrayWithHoles(arr) || _iterableToArrayLimit(arr, i) || _nonIterableRest(); }

function _nonIterableRest() { throw new TypeError("Invalid attempt to destructure non-iterable instance"); }

function _iterableToArrayLimit(arr, i) { if (!(Symbol.iterator in Object(arr) || Object.prototype.toString.call(arr) === "[object Arguments]")) { return; } var _arr = []; var _n = true; var _d = false; var _e = undefined; try { for (var _i = arr[Symbol.iterator](), _s; !(_n = (_s = _i.next()).done); _n = true) { _arr.push(_s.value); if (i && _arr.length === i) break; } } catch (err) { _d = true; _e = err; } finally { try { if (!_n && _i["return"] != null) _i["return"](); } finally { if (_d) throw _e; } } return _arr; }

function _arrayWithHoles(arr) { if (Array.isArray(arr)) return arr; }

/**
 * Handsontable UndoRedo class
 */
import Hooks from './../../pluginHooks';
import { arrayMap, arrayEach } from './../../helpers/array';
import { rangeEach } from './../../helpers/number';
import { inherit, deepClone } from './../../helpers/object';
import { stopImmediatePropagation, isImmediatePropagationStopped } from './../../helpers/dom/event';
import { align } from './../contextMenu/utils';
/**
 * @description
 * Handsontable UndoRedo plugin allows to undo and redo certain actions done in the table.
 *
 * __Note__, that not all actions are currently undo-able. The UndoRedo plugin is enabled by default.
 *
 * @example
 * ```js
 * undo: true
 * ```
 * @class UndoRedo
 * @plugin UndoRedo
 */

function UndoRedo(instance) {
  var plugin = this;
  this.instance = instance;
  this.doneActions = [];
  this.undoneActions = [];
  this.ignoreNewActions = false;
  instance.addHook('afterChange', function (changes, source) {
    var changesLen = changes && changes.length;

    if (!changesLen || ['UndoRedo.undo', 'UndoRedo.redo', 'MergeCells'].includes(source)) {
      return;
    }

    var hasDifferences = changes.find(function (change) {
      var _change = _slicedToArray(change, 4),
          oldValue = _change[2],
          newValue = _change[3];

      return oldValue !== newValue;
    });

    if (!hasDifferences) {
      return;
    }

    var clonedChanges = changes.reduce(function (arr, change) {
      arr.push(_toConsumableArray(change));
      return arr;
    }, []);
    arrayEach(clonedChanges, function (change) {
      change[1] = instance.propToCol(change[1]);
    });
    var selected = changesLen > 1 ? this.getSelected() : [[clonedChanges[0][0], clonedChanges[0][1]]];
    plugin.done(new UndoRedo.ChangeAction(clonedChanges, selected));
  });
  instance.addHook('afterCreateRow', function (index, amount, source) {
    if (source === 'UndoRedo.undo' || source === 'UndoRedo.undo' || source === 'auto') {
      return;
    }

    var action = new UndoRedo.CreateRowAction(index, amount);
    plugin.done(action);
  });
  instance.addHook('beforeRemoveRow', function (index, amount, logicRows, source) {
    if (source === 'UndoRedo.undo' || source === 'UndoRedo.redo' || source === 'auto') {
      return;
    }

    var originalData = plugin.instance.getSourceDataArray();
    var rowIndex = (originalData.length + index) % originalData.length;
    var physicalRowIndex = instance.toPhysicalRow(rowIndex);
    var removedData = deepClone(originalData.slice(physicalRowIndex, physicalRowIndex + amount));
    plugin.done(new UndoRedo.RemoveRowAction(rowIndex, removedData));
  });
  instance.addHook('afterCreateCol', function (index, amount, source) {
    if (source === 'UndoRedo.undo' || source === 'UndoRedo.redo' || source === 'auto') {
      return;
    }

    plugin.done(new UndoRedo.CreateColumnAction(index, amount));
  });
  instance.addHook('beforeRemoveCol', function (index, amount, logicColumns, source) {
    if (source === 'UndoRedo.undo' || source === 'UndoRedo.redo' || source === 'auto') {
      return;
    }

    var originalData = plugin.instance.getSourceDataArray();
    var columnIndex = (plugin.instance.countCols() + index) % plugin.instance.countCols();
    var removedData = [];
    var headers = [];
    var indexes = [];
    rangeEach(originalData.length - 1, function (i) {
      var column = [];
      var origRow = originalData[i];
      rangeEach(columnIndex, columnIndex + (amount - 1), function (j) {
        column.push(origRow[instance.runHooks('modifyCol', j)]);
      });
      removedData.push(column);
    });
    rangeEach(amount - 1, function (i) {
      indexes.push(instance.runHooks('modifyCol', columnIndex + i));
    });

    if (Array.isArray(instance.getSettings().colHeaders)) {
      rangeEach(amount - 1, function (i) {
        headers.push(instance.getSettings().colHeaders[instance.runHooks('modifyCol', columnIndex + i)] || null);
      });
    }

    var manualColumnMovePlugin = plugin.instance.getPlugin('manualColumnMove');
    var columnsMap = manualColumnMovePlugin.isEnabled() ? manualColumnMovePlugin.columnsMapper.__arrayMap : [];
    var action = new UndoRedo.RemoveColumnAction(columnIndex, indexes, removedData, headers, columnsMap);
    plugin.done(action);
  });
  instance.addHook('beforeCellAlignment', function (stateBefore, range, type, alignment) {
    var action = new UndoRedo.CellAlignmentAction(stateBefore, range, type, alignment);
    plugin.done(action);
  });
  instance.addHook('beforeFilter', function (conditionsStack) {
    plugin.done(new UndoRedo.FiltersAction(conditionsStack));
  });
  instance.addHook('beforeRowMove', function (movedRows, target) {
    if (movedRows === false) {
      return;
    }

    plugin.done(new UndoRedo.RowMoveAction(movedRows, target));
  });
  instance.addHook('beforeMergeCells', function (cellRange, auto) {
    if (auto) {
      return;
    }

    plugin.done(new UndoRedo.MergeCellsAction(instance, cellRange));
  });
  instance.addHook('afterUnmergeCells', function (cellRange, auto) {
    if (auto) {
      return;
    }

    plugin.done(new UndoRedo.UnmergeCellsAction(instance, cellRange));
  });
}

UndoRedo.prototype.done = function (action) {
  if (!this.ignoreNewActions) {
    this.doneActions.push(action);
    this.undoneActions.length = 0;
  }
};
/**
 * Undo the last action performed to the table.
 *
 * @function undo
 * @memberof UndoRedo#
 * @fires Hooks#beforeUndo
 * @fires Hooks#afterUndo
 */


UndoRedo.prototype.undo = function () {
  if (this.isUndoAvailable()) {
    var action = this.doneActions.pop();
    var actionClone = deepClone(action);
    var instance = this.instance;
    var continueAction = instance.runHooks('beforeUndo', actionClone);

    if (continueAction === false) {
      return;
    }

    this.ignoreNewActions = true;
    var that = this;
    action.undo(this.instance, function () {
      that.ignoreNewActions = false;
      that.undoneActions.push(action);
    });
    instance.runHooks('afterUndo', actionClone);
  }
};
/**
 * Redo the previous action performed to the table (used to reverse an undo).
 *
 * @function redo
 * @memberof UndoRedo#
 * @fires Hooks#beforeRedo
 * @fires Hooks#afterRedo
 */


UndoRedo.prototype.redo = function () {
  if (this.isRedoAvailable()) {
    var action = this.undoneActions.pop();
    var actionClone = deepClone(action);
    var instance = this.instance;
    var continueAction = instance.runHooks('beforeRedo', actionClone);

    if (continueAction === false) {
      return;
    }

    this.ignoreNewActions = true;
    var that = this;
    action.redo(this.instance, function () {
      that.ignoreNewActions = false;
      that.doneActions.push(action);
    });
    instance.runHooks('afterRedo', actionClone);
  }
};
/**
 * Checks if undo action is available.
 *
 * @function isUndoAvailable
 * @memberof UndoRedo#
 * @return {Boolean} Return `true` if undo can be performed, `false` otherwise.
 */


UndoRedo.prototype.isUndoAvailable = function () {
  return this.doneActions.length > 0;
};
/**
 * Checks if redo action is available.
 *
 * @function isRedoAvailable
 * @memberof UndoRedo#
 * @return {Boolean} Return `true` if redo can be performed, `false` otherwise.
 */


UndoRedo.prototype.isRedoAvailable = function () {
  return this.undoneActions.length > 0;
};
/**
 * Clears undo history.
 *
 * @function clear
 * @memberof UndoRedo#
 */


UndoRedo.prototype.clear = function () {
  this.doneActions.length = 0;
  this.undoneActions.length = 0;
};

UndoRedo.Action = function () {};

UndoRedo.Action.prototype.undo = function () {};

UndoRedo.Action.prototype.redo = function () {};
/**
 * Change action.
 *
 * @private
 */


UndoRedo.ChangeAction = function (changes, selected) {
  this.changes = changes;
  this.selected = selected;
  this.actionType = 'change';
};

inherit(UndoRedo.ChangeAction, UndoRedo.Action);

UndoRedo.ChangeAction.prototype.undo = function (instance, undoneCallback) {
  var data = deepClone(this.changes);
  var emptyRowsAtTheEnd = instance.countEmptyRows(true);
  var emptyColsAtTheEnd = instance.countEmptyCols(true);

  for (var i = 0, len = data.length; i < len; i++) {
    data[i].splice(3, 1);
  }

  instance.addHookOnce('afterChange', undoneCallback);
  instance.setDataAtCell(data, null, null, 'UndoRedo.undo');

  for (var _i2 = 0, _len = data.length; _i2 < _len; _i2++) {
    var _data$_i = _slicedToArray(data[_i2], 2),
        row = _data$_i[0],
        column = _data$_i[1];

    if (instance.getSettings().minSpareRows && row + 1 + instance.getSettings().minSpareRows === instance.countRows() && emptyRowsAtTheEnd === instance.getSettings().minSpareRows) {
      instance.alter('remove_row', parseInt(row + 1, 10), instance.getSettings().minSpareRows);
      instance.undoRedo.doneActions.pop();
    }

    if (instance.getSettings().minSpareCols && column + 1 + instance.getSettings().minSpareCols === instance.countCols() && emptyColsAtTheEnd === instance.getSettings().minSpareCols) {
      instance.alter('remove_col', parseInt(column + 1, 10), instance.getSettings().minSpareCols);
      instance.undoRedo.doneActions.pop();
    }
  }

  instance.selectCells(this.selected, false, false);
};

UndoRedo.ChangeAction.prototype.redo = function (instance, onFinishCallback) {
  var data = deepClone(this.changes);

  for (var i = 0, len = data.length; i < len; i++) {
    data[i].splice(2, 1);
  }

  instance.addHookOnce('afterChange', onFinishCallback);
  instance.setDataAtCell(data, null, null, 'UndoRedo.redo');

  if (this.selected) {
    instance.selectCells(this.selected, false, false);
  }
};
/**
 * Create row action.
 *
 * @private
 */


UndoRedo.CreateRowAction = function (index, amount) {
  this.index = index;
  this.amount = amount;
  this.actionType = 'insert_row';
};

inherit(UndoRedo.CreateRowAction, UndoRedo.Action);

UndoRedo.CreateRowAction.prototype.undo = function (instance, undoneCallback) {
  var rowCount = instance.countRows();
  var minSpareRows = instance.getSettings().minSpareRows;

  if (this.index >= rowCount && this.index - minSpareRows < rowCount) {
    this.index -= minSpareRows; // work around the situation where the needed row was removed due to an 'undo' of a made change
  }

  instance.addHookOnce('afterRemoveRow', undoneCallback);
  instance.alter('remove_row', this.index, this.amount, 'UndoRedo.undo');
};

UndoRedo.CreateRowAction.prototype.redo = function (instance, redoneCallback) {
  instance.addHookOnce('afterCreateRow', redoneCallback);
  instance.alter('insert_row', this.index, this.amount, 'UndoRedo.redo');
};
/**
 * Remove row action.
 *
 * @private
 */


UndoRedo.RemoveRowAction = function (index, data) {
  this.index = index;
  this.data = data;
  this.actionType = 'remove_row';
};

inherit(UndoRedo.RemoveRowAction, UndoRedo.Action);

UndoRedo.RemoveRowAction.prototype.undo = function (instance, undoneCallback) {
  instance.alter('insert_row', this.index, this.data.length, 'UndoRedo.undo');
  instance.addHookOnce('afterRender', undoneCallback);
  instance.populateFromArray(this.index, 0, this.data, void 0, void 0, 'UndoRedo.undo');
};

UndoRedo.RemoveRowAction.prototype.redo = function (instance, redoneCallback) {
  instance.addHookOnce('afterRemoveRow', redoneCallback);
  instance.alter('remove_row', this.index, this.data.length, 'UndoRedo.redo');
};
/**
 * Create column action.
 *
 * @private
 */


UndoRedo.CreateColumnAction = function (index, amount) {
  this.index = index;
  this.amount = amount;
  this.actionType = 'insert_col';
};

inherit(UndoRedo.CreateColumnAction, UndoRedo.Action);

UndoRedo.CreateColumnAction.prototype.undo = function (instance, undoneCallback) {
  instance.addHookOnce('afterRemoveCol', undoneCallback);
  instance.alter('remove_col', this.index, this.amount, 'UndoRedo.undo');
};

UndoRedo.CreateColumnAction.prototype.redo = function (instance, redoneCallback) {
  instance.addHookOnce('afterCreateCol', redoneCallback);
  instance.alter('insert_col', this.index, this.amount, 'UndoRedo.redo');
};
/**
 * Remove column action.
 *
 * @private
 */


UndoRedo.RemoveColumnAction = function (index, indexes, data, headers, columnPositions) {
  this.index = index;
  this.indexes = indexes;
  this.data = data;
  this.amount = this.data[0].length;
  this.headers = headers;
  this.columnPositions = columnPositions.slice(0);
  this.actionType = 'remove_col';
};

inherit(UndoRedo.RemoveColumnAction, UndoRedo.Action);

UndoRedo.RemoveColumnAction.prototype.undo = function (instance, undoneCallback) {
  var _this = this;

  var row;
  var ascendingIndexes = this.indexes.slice(0).sort();

  var sortByIndexes = function sortByIndexes(elem, j, arr) {
    return arr[_this.indexes.indexOf(ascendingIndexes[j])];
  };

  var sortedData = [];
  rangeEach(this.data.length - 1, function (i) {
    sortedData[i] = arrayMap(_this.data[i], sortByIndexes);
  });
  var sortedHeaders = [];
  sortedHeaders = arrayMap(this.headers, sortByIndexes);
  var changes = []; // TODO: Temporary hook for undo/redo mess

  instance.runHooks('beforeCreateCol', this.indexes[0], this.indexes.length, 'UndoRedo.undo');
  rangeEach(this.data.length - 1, function (i) {
    row = instance.getSourceDataAtRow(i);
    rangeEach(ascendingIndexes.length - 1, function (j) {
      row.splice(ascendingIndexes[j], 0, sortedData[i][j]);
      changes.push([i, ascendingIndexes[j], null, sortedData[i][j]]);
    });
  }); // TODO: Temporary hook for undo/redo mess

  if (instance.getPlugin('formulas')) {
    instance.getPlugin('formulas').onAfterSetDataAtCell(changes);
  }

  if (typeof this.headers !== 'undefined') {
    rangeEach(sortedHeaders.length - 1, function (j) {
      instance.getSettings().colHeaders.splice(ascendingIndexes[j], 0, sortedHeaders[j]);
    });
  }

  if (instance.getPlugin('manualColumnMove')) {
    instance.getPlugin('manualColumnMove').columnsMapper.__arrayMap = this.columnPositions;
  }

  instance.addHookOnce('afterRender', undoneCallback); // TODO: Temporary hook for undo/redo mess

  instance.runHooks('afterCreateCol', this.indexes[0], this.indexes.length, 'UndoRedo.undo');

  if (instance.getPlugin('formulas')) {
    instance.getPlugin('formulas').recalculateFull();
  }

  instance.render();
};

UndoRedo.RemoveColumnAction.prototype.redo = function (instance, redoneCallback) {
  instance.addHookOnce('afterRemoveCol', redoneCallback);
  instance.alter('remove_col', this.index, this.amount, 'UndoRedo.redo');
};
/**
 * Cell alignment action.
 *
 * @private
 */


UndoRedo.CellAlignmentAction = function (stateBefore, range, type, alignment) {
  this.stateBefore = stateBefore;
  this.range = range;
  this.type = type;
  this.alignment = alignment;
};

UndoRedo.CellAlignmentAction.prototype.undo = function (instance, undoneCallback) {
  var _this2 = this;

  arrayEach(this.range, function (_ref) {
    var from = _ref.from,
        to = _ref.to;

    for (var row = from.row; row <= to.row; row += 1) {
      for (var col = from.col; col <= to.col; col += 1) {
        instance.setCellMeta(row, col, 'className', _this2.stateBefore[row][col] || ' htLeft');
      }
    }
  });
  instance.addHookOnce('afterRender', undoneCallback);
  instance.render();
};

UndoRedo.CellAlignmentAction.prototype.redo = function (instance, undoneCallback) {
  align(this.range, this.type, this.alignment, function (row, col) {
    return instance.getCellMeta(row, col);
  }, function (row, col, key, value) {
    return instance.setCellMeta(row, col, key, value);
  });
  instance.addHookOnce('afterRender', undoneCallback);
  instance.render();
};
/**
 * Filters action.
 *
 * @private
 */


UndoRedo.FiltersAction = function (conditionsStack) {
  this.conditionsStack = conditionsStack;
  this.actionType = 'filter';
};

inherit(UndoRedo.FiltersAction, UndoRedo.Action);

UndoRedo.FiltersAction.prototype.undo = function (instance, undoneCallback) {
  var filters = instance.getPlugin('filters');
  instance.addHookOnce('afterRender', undoneCallback);
  filters.conditionCollection.importAllConditions(this.conditionsStack.slice(0, this.conditionsStack.length - 1));
  filters.filter();
};

UndoRedo.FiltersAction.prototype.redo = function (instance, redoneCallback) {
  var filters = instance.getPlugin('filters');
  instance.addHookOnce('afterRender', redoneCallback);
  filters.conditionCollection.importAllConditions(this.conditionsStack);
  filters.filter();
};
/**
 * Merge Cells action.
 * @util
 */


var MergeCellsAction =
/*#__PURE__*/
function (_UndoRedo$Action) {
  _inherits(MergeCellsAction, _UndoRedo$Action);

  function MergeCellsAction(instance, cellRange) {
    var _this3;

    _classCallCheck(this, MergeCellsAction);

    _this3 = _possibleConstructorReturn(this, _getPrototypeOf(MergeCellsAction).call(this));
    _this3.cellRange = cellRange;
    _this3.rangeData = instance.getData(cellRange.from.row, cellRange.from.col, cellRange.to.row, cellRange.to.col);
    return _this3;
  }

  _createClass(MergeCellsAction, [{
    key: "undo",
    value: function undo(instance, undoneCallback) {
      var mergeCellsPlugin = instance.getPlugin('mergeCells');
      instance.addHookOnce('afterRender', undoneCallback);
      mergeCellsPlugin.unmergeRange(this.cellRange, true);
      instance.populateFromArray(this.cellRange.from.row, this.cellRange.from.col, this.rangeData, void 0, void 0, 'MergeCells');
    }
  }, {
    key: "redo",
    value: function redo(instance, redoneCallback) {
      var mergeCellsPlugin = instance.getPlugin('mergeCells');
      instance.addHookOnce('afterRender', redoneCallback);
      mergeCellsPlugin.mergeRange(this.cellRange);
    }
  }]);

  return MergeCellsAction;
}(UndoRedo.Action);

UndoRedo.MergeCellsAction = MergeCellsAction;
/**
 * Unmerge Cells action.
 * @util
 */

var UnmergeCellsAction =
/*#__PURE__*/
function (_UndoRedo$Action2) {
  _inherits(UnmergeCellsAction, _UndoRedo$Action2);

  function UnmergeCellsAction(instance, cellRange) {
    var _this4;

    _classCallCheck(this, UnmergeCellsAction);

    _this4 = _possibleConstructorReturn(this, _getPrototypeOf(UnmergeCellsAction).call(this));
    _this4.cellRange = cellRange;
    return _this4;
  }

  _createClass(UnmergeCellsAction, [{
    key: "undo",
    value: function undo(instance, undoneCallback) {
      var mergeCellsPlugin = instance.getPlugin('mergeCells');
      instance.addHookOnce('afterRender', undoneCallback);
      mergeCellsPlugin.mergeRange(this.cellRange, true);
    }
  }, {
    key: "redo",
    value: function redo(instance, redoneCallback) {
      var mergeCellsPlugin = instance.getPlugin('mergeCells');
      instance.addHookOnce('afterRender', redoneCallback);
      mergeCellsPlugin.unmergeRange(this.cellRange, true);
      instance.render();
    }
  }]);

  return UnmergeCellsAction;
}(UndoRedo.Action);

UndoRedo.UnmergeCellsAction = UnmergeCellsAction;
/**
 * ManualRowMove action.
 *
 * @private
 * @TODO: removeRow undo should works on logical index
 */

UndoRedo.RowMoveAction = function (movedRows, target) {
  this.rows = movedRows.slice();
  this.target = target;
};

inherit(UndoRedo.RowMoveAction, UndoRedo.Action);

UndoRedo.RowMoveAction.prototype.undo = function (instance, undoneCallback) {
  var manualRowMove = instance.getPlugin('manualRowMove');
  instance.addHookOnce('afterRender', undoneCallback);
  var mod = this.rows[0] < this.target ? -1 * this.rows.length : 0;
  var newTarget = this.rows[0] > this.target ? this.rows[0] + this.rows.length : this.rows[0];
  var newRows = [];
  var rowsLen = this.rows.length + mod;

  for (var i = mod; i < rowsLen; i += 1) {
    newRows.push(this.target + i);
  }

  manualRowMove.moveRows(newRows.slice(), newTarget);
  instance.render();
  instance.selectCell(this.rows[0], 0, this.rows[this.rows.length - 1], instance.countCols() - 1, false, false);
};

UndoRedo.RowMoveAction.prototype.redo = function (instance, redoneCallback) {
  var manualRowMove = instance.getPlugin('manualRowMove');
  instance.addHookOnce('afterRender', redoneCallback);
  manualRowMove.moveRows(this.rows.slice(), this.target);
  instance.render();
  var startSelection = this.rows[0] < this.target ? this.target - this.rows.length : this.target;
  instance.selectCell(startSelection, 0, startSelection + this.rows.length - 1, instance.countCols() - 1, false, false);
};

function init() {
  var instance = this;
  var pluginEnabled = typeof instance.getSettings().undo === 'undefined' || instance.getSettings().undo;

  if (pluginEnabled) {
    if (!instance.undoRedo) {
      /**
       * Instance of Handsontable.UndoRedo Plugin {@link Handsontable.UndoRedo}
       *
       * @alias undoRedo
       * @memberof! Handsontable.Core#
       * @type {UndoRedo}
       */
      instance.undoRedo = new UndoRedo(instance);
      exposeUndoRedoMethods(instance);
      instance.addHook('beforeKeyDown', onBeforeKeyDown);
      instance.addHook('afterChange', onAfterChange);
    }
  } else if (instance.undoRedo) {
    delete instance.undoRedo;
    removeExposedUndoRedoMethods(instance);
    instance.removeHook('beforeKeyDown', onBeforeKeyDown);
    instance.removeHook('afterChange', onAfterChange);
  }
}

function onBeforeKeyDown(event) {
  if (isImmediatePropagationStopped(event)) {
    return;
  }

  var instance = this;
  var editor = instance.getActiveEditor();

  if (editor && editor.isOpened()) {
    return;
  }

  var altKey = event.altKey,
      ctrlKey = event.ctrlKey,
      keyCode = event.keyCode,
      metaKey = event.metaKey,
      shiftKey = event.shiftKey;
  var isCtrlDown = (ctrlKey || metaKey) && !altKey;

  if (!isCtrlDown) {
    return;
  }

  var isRedoHotkey = keyCode === 89 || shiftKey && keyCode === 90;

  if (isRedoHotkey) {
    // CTRL + Y or CTRL + SHIFT + Z
    instance.undoRedo.redo();
    stopImmediatePropagation(event);
  } else if (keyCode === 90) {
    // CTRL + Z
    instance.undoRedo.undo();
    stopImmediatePropagation(event);
  }
}

function onAfterChange(changes, source) {
  var instance = this;

  if (source === 'loadData') {
    return instance.undoRedo.clear();
  }
}

function exposeUndoRedoMethods(instance) {
  /**
   * {@link UndoRedo#undo}
   * @alias undo
   * @memberof! Handsontable.Core#
   */
  instance.undo = function () {
    return instance.undoRedo.undo();
  };
  /**
   * {@link UndoRedo#redo}
   * @alias redo
   * @memberof! Handsontable.Core#
   */


  instance.redo = function () {
    return instance.undoRedo.redo();
  };
  /**
   * {@link UndoRedo#isUndoAvailable}
   * @alias isUndoAvailable
   * @memberof! Handsontable.Core#
   */


  instance.isUndoAvailable = function () {
    return instance.undoRedo.isUndoAvailable();
  };
  /**
   * {@link UndoRedo#isRedoAvailable}
   * @alias isRedoAvailable
   * @memberof! Handsontable.Core#
   */


  instance.isRedoAvailable = function () {
    return instance.undoRedo.isRedoAvailable();
  };
  /**
   * {@link UndoRedo#clear}
   * @alias clearUndo
   * @memberof! Handsontable.Core#
   */


  instance.clearUndo = function () {
    return instance.undoRedo.clear();
  };
}

function removeExposedUndoRedoMethods(instance) {
  delete instance.undo;
  delete instance.redo;
  delete instance.isUndoAvailable;
  delete instance.isRedoAvailable;
  delete instance.clearUndo;
}

var hook = Hooks.getSingleton();
hook.add('afterInit', init);
hook.add('afterUpdateSettings', init);
hook.register('beforeUndo');
hook.register('afterUndo');
hook.register('beforeRedo');
hook.register('afterRedo');
export default UndoRedo;