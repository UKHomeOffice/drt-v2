"use strict";

require("core-js/modules/es.object.to-string");

require("core-js/modules/es.regexp.to-string");

exports.__esModule = true;
exports.operate = operate;
exports.OPERATION_NAME = void 0;

var _array = require("../../../helpers/array");

var _utils = require("../utils");

var _value = _interopRequireDefault(require("../cell/value"));

var _expressionModifier = _interopRequireDefault(require("../expressionModifier"));

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

/**
 * When "inser_column" is triggered the following operations must be performed:
 *
 * - All formulas which contain cell coordinates must be updated and saved into source data - Column must be increased
 *   by "amount" of times (eq: D4 to E4, $F$5 to $G$5);
 * - Mark all formulas which need update with "STATE_OUT_OFF_DATE" flag, so they can be recalculated after the operation.
 */
var OPERATION_NAME = 'insert_column';
/**
 * Execute changes.
 *
 * @param {Number} start Index column from which the operation starts.
 * @param {Number} amount Count of columns to be inserted.
 * @param {Boolean} [modifyFormula=true] If `true` all formula expressions will be modified according to the changes.
 *                                       `false` value is used by UndoRedo plugin which saves snapshoots before alter
 *                                       operation so it doesn't have to modify formulas if "undo" action was triggered.
 */

exports.OPERATION_NAME = OPERATION_NAME;

function operate(start, amount) {
  var modifyFormula = arguments.length > 2 && arguments[2] !== undefined ? arguments[2] : true;
  var matrix = this.matrix,
      dataProvider = this.dataProvider;
  var translate = [0, amount];
  (0, _array.arrayEach)(matrix.cellReferences, function (cell) {
    if (cell.column >= start) {
      cell.translateTo.apply(cell, translate);
    }
  });
  (0, _array.arrayEach)(matrix.data, function (cell) {
    var origRow = cell.row,
        origColumn = cell.column;

    if (cell.column >= start) {
      cell.translateTo.apply(cell, translate);
      cell.setState(_value.default.STATE_OUT_OFF_DATE);
    }

    if (modifyFormula) {
      var row = cell.row,
          column = cell.column;
      var value = dataProvider.getSourceDataAtCell(row, column);

      if ((0, _utils.isFormulaExpression)(value)) {
        var startCoord = (0, _utils.cellCoordFactory)('column', start);
        var expModifier = new _expressionModifier.default(value);
        expModifier.useCustomModifier(customTranslateModifier);
        expModifier.translate({
          column: amount
        }, startCoord({
          row: origRow,
          column: origColumn
        }));
        dataProvider.updateSourceData(row, column, expModifier.toString());
      }
    }
  });
}

function customTranslateModifier(cell, axis, delta, startFromIndex) {
  var start = cell.start,
      end = cell.end;
  var startIndex = start[axis].index;
  var endIndex = end[axis].index;
  var deltaStart = delta;
  var deltaEnd = delta; // Do not translate cells above inserted row or on the left of inserted column

  if (startFromIndex > startIndex) {
    deltaStart = 0;
  }

  if (startFromIndex > endIndex) {
    deltaEnd = 0;
  }

  return [deltaStart, deltaEnd, false];
}