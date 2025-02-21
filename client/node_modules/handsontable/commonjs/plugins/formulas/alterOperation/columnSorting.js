"use strict";

require("core-js/modules/es.array.iterator");

require("core-js/modules/es.object.to-string");

require("core-js/modules/es.regexp.to-string");

require("core-js/modules/es.string.iterator");

require("core-js/modules/es.weak-map");

require("core-js/modules/web.dom-collections.iterator");

exports.__esModule = true;
exports.prepare = prepare;
exports.operate = operate;
exports.OPERATION_NAME = void 0;

var _array = require("../../../helpers/array");

var _utils = require("../utils");

var _value = _interopRequireDefault(require("../cell/value"));

var _expressionModifier = _interopRequireDefault(require("../expressionModifier"));

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

/**
 * When "column_sorting" is triggered the following operations must be performed:
 *
 * - All formulas which contain cell coordinates must be updated and saved into source data - Column must be changed
 *   (decreased or increased) depends on new target position - previous position.
 * - Mark all formulas which need update with "STATE_OUT_OFF_DATE" flag, so they can be recalculated after the operation.
 */
var OPERATION_NAME = 'column_sorting';
exports.OPERATION_NAME = OPERATION_NAME;
var visualRows;
/**
 * Collect all previous visual rows from CellValues.
 */

function prepare() {
  var matrix = this.matrix,
      dataProvider = this.dataProvider;
  visualRows = new WeakMap();
  (0, _array.arrayEach)(matrix.data, function (cell) {
    visualRows.set(cell, dataProvider.t.toVisualRow(cell.row));
  });
}
/**
 * Translate all CellValues depends on previous position.
 */


function operate() {
  var matrix = this.matrix,
      dataProvider = this.dataProvider;
  matrix.cellReferences.length = 0;
  (0, _array.arrayEach)(matrix.data, function (cell) {
    cell.setState(_value.default.STATE_OUT_OFF_DATE);
    cell.clearPrecedents();
    var row = cell.row,
        column = cell.column;
    var value = dataProvider.getSourceDataAtCell(row, column);

    if ((0, _utils.isFormulaExpression)(value)) {
      var prevRow = visualRows.get(cell);
      var expModifier = new _expressionModifier.default(value);
      expModifier.translate({
        row: dataProvider.t.toVisualRow(row) - prevRow
      });
      dataProvider.updateSourceData(row, column, expModifier.toString());
    }
  });
  visualRows = null;
}